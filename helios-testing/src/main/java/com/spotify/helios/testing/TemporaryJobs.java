/*
 * Copyright (c) 2014 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.testing;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import com.spotify.helios.client.HeliosClient;
import com.spotify.helios.common.Json;
import com.spotify.helios.common.descriptors.Job;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import com.typesafe.config.ConfigValueType;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Optional.fromNullable;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Integer.toHexString;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class TemporaryJobs implements TestRule {

  private static final Logger log = LoggerFactory.getLogger(TemporaryJobs.class);

  private static final String HELIOS_TESTING_PROFILE = "helios.testing.profile";
  private static final String HELIOS_TESTING_PROFILES = "helios.testing.profiles.";
  private static final Prober DEFAULT_PROBER = new DefaultProber();
  private static final long JOB_HEALTH_CHECK_INTERVAL_MILLIS = SECONDS.toMillis(5);
  private static final long DEFAULT_DEPLOY_TIMEOUT_MILLIS = MINUTES.toMillis(10);

  private final HeliosDeployment heliosDeployment;
  private final HeliosClient client;
  private final Prober prober;
  private final String defaultHostFilter;
  private final Config config;
  private final Map<String, String> env;
  private final List<TemporaryJob> jobs = Lists.newCopyOnWriteArrayList();
  private final Deployer deployer;
  private final Undeployer undeployer;
  private final String jobPrefix;

  private final ExecutorService executor = MoreExecutors.getExitingExecutorService(
      (ThreadPoolExecutor) Executors.newFixedThreadPool(
          1, new ThreadFactoryBuilder()
              .setNameFormat("helios-test-runner-%d")
              .setDaemon(true)
              .build()),
      0, SECONDS);

  private static volatile HeliosDeployment soloDeployment;

  TemporaryJobs(final Builder builder, final Config config) {
    this.heliosDeployment = firstNonNull(builder.heliosDeployment,
                                         getOrCreateHeliosSoloDeployment());
    client = checkNotNull(heliosDeployment.client(), "client");
    this.prober = checkNotNull(builder.prober, "prober");
    this.defaultHostFilter = checkNotNull(builder.hostFilter, "hostFilter");
    this.env = checkNotNull(builder.env, "env");
    this.jobPrefix = checkNotNull(builder.jobPrefix, "jobPrefix");

    checkArgument(builder.deployTimeoutMillis >= 0, "deployTimeoutMillis");

    this.undeployer = new DefaultUndeployer(client);
    this.deployer = fromNullable(builder.deployer).or(
        new DefaultDeployer(client, jobs, builder.hostPickingStrategy,
            builder.jobDeployedMessageFormat, builder.deployTimeoutMillis, undeployer));

    // Load in the prefix so it can be used in the config
    final Config configWithPrefix = ConfigFactory.empty()
        .withValue("prefix", ConfigValueFactory.fromAnyRef(jobPrefix));

    this.config = config.withFallback(configWithPrefix).resolve();
  }

  private static synchronized HeliosDeployment getOrCreateHeliosSoloDeployment() {
    if (soloDeployment == null) {
      // TODO (dxia) remove checkForNewImages(). Set here to prevent using
      // spotify/helios-solo:latest from docker hub
      soloDeployment = HeliosSoloDeployment.fromEnv()
        .checkForNewImages(false)
        .build();
    }

    return soloDeployment;
  }

  /**
   * Perform setup. This is normally called by JUnit when TemporaryJobs is used with @Rule.
   * If @Rule cannot be used, call this method before calling {@link #job()}.
   *
   * Note: When not being used as a @Rule, jobs will not be monitored during test runs.
   */
  public void before() {
    deployer.readyToDeploy();
  }

  /**
   * Perform teardown. This is normally called by JUnit when TemporaryJobs is used with @Rule.
   * If @Rule cannot be used, call this method after running tests.
   */
  public void after() {
    // Stop the test runner thread
    executor.shutdownNow();
    try {
      final boolean terminated = executor.awaitTermination(30, SECONDS);
      if (!terminated) {
        log.warn("Failed to stop test runner thread");
      }
    } catch (InterruptedException ignore) {
    }

    final List<AssertionError> errors = new ArrayList<>();

    for (final TemporaryJob job : jobs) {
      // Undeploying a job doesn't guaruntee the container will be stopped immediately.
      // Luckily TaskRunner tells docker to wait 120 seconds after stopping to kill the container.
      // So containers that don't immediately stop **should** only stay around for at most 120
      // seconds.
      // TODO (dxia) `TemporaryJobs` doesn't need to clean up jobs in the helios-solo container
      // because the container's `start.sh` already has this logic. But SimpleTest creates an
      // in-memory Helios cluster and assumes jobs are undeployed when this method is called.
      // So we should update those tests that use TemporaryJobs and start helios-solo and then
      // simply ignore it. Once that happens, we can delete this line and remove all Undeployer
      // references in this class.
      errors.addAll(undeployer.undeploy(job.job(), job.hosts()));
    }

    for (final AssertionError error : errors) {
      log.error(error.getMessage());
    }

    // Don't delete the prefix file if any errors occurred during undeployment, so that we'll
    // try to undeploy them the next time TemporaryJobs is run.
    if (errors.isEmpty()) {
      heliosDeployment.cleanup();
    }

    // TODO (dxia) We don't want to close when it's HSD, but we do when it's
    // ExistingHeliosDeployment
    // heliosDeployment.close();
  }

  public TemporaryJobBuilder job() {
    return this.job(Job.newBuilder());
  }

  public TemporaryJobBuilder jobWithConfig(final String configFile) throws IOException {
    checkNotNull(configFile);

    final Path configPath = Paths.get(configFile);
    final File file = configPath.toFile();

    if (!file.exists() || !file.isFile() || !file.canRead()) {
      throw new IllegalArgumentException("Cannot read file " + file);
    }

    final byte[] bytes = Files.readAllBytes(configPath);
    final String config = new String(bytes, UTF_8);
    final Job job = Json.read(config, Job.class);

    return this.job(job.toBuilder());
  }

  private TemporaryJobBuilder job(final Job.Builder jobBuilder) {
    final TemporaryJobBuilder builder = new TemporaryJobBuilder(
        deployer, jobPrefix, prober, env, jobBuilder);

    if (config.hasPath("env")) {
      final Config env = config.getConfig("env");

      for (final Entry<String, ConfigValue> entry : env.entrySet()) {
        builder.env(entry.getKey(), entry.getValue().unwrapped());
      }
    }

    if (config.hasPath("version")) {
      builder.version(config.getString("version"));
    }
    if (config.hasPath("image")) {
      builder.image(config.getString("image"));
    }
    if (config.hasPath("command")) {
      builder.command(getListByKey("command", config));
    }
    if (config.hasPath("host")) {
      builder.host(config.getString("host"));
    }
    if (config.hasPath("deploy")) {
      builder.deploy(getListByKey("deploy", config));
    }
    if (config.hasPath("imageInfoFile")) {
      builder.imageFromInfoFile(config.getString("imageInfoFile"));
    }
    if (config.hasPath("registrationDomain")) {
      builder.registrationDomain(config.getString("registrationDomain"));
    }
    // port and expires intentionally left out -- since expires is a specific point in time, I
    // cannot imagine a config-file use for it, additionally for ports, I'm thinking that port
    // allocations are not likely to be common -- but PR's welcome if I'm wrong. - drewc@spotify.com
    builder.hostFilter(defaultHostFilter);
    return builder;
  }

  private static List<String> getListByKey(final String key, final Config config) {
    final ConfigList endpointList = config.getList(key);
    final List<String> stringList = Lists.newArrayList();
    for (final ConfigValue v : endpointList) {
      if (v.valueType() != ConfigValueType.STRING) {
        throw new RuntimeException("Item in " + key + " list [" + v + "] is not a string");
      }
      stringList.add((String) v.unwrapped());
    }
    return stringList;
  }

  /**
   * Creates a new instance of TemporaryJobs. Will attempt start a helios-solo container which
   * has a master, ZooKeeper, and one agent.
   *
   * @return an instance of TemporaryJobs
   * @see <a href=
   * "https://github.com/spotify/helios/blob/master/docs/testing_framework.md#configuration-by-file"
   * >Helios Testing Framework - Configuration By File</a>
   */
  public static TemporaryJobs create() {
    return builder().build();
  }

  public static TemporaryJobs create(final HeliosClient client) {
    return builder().heliosDeployment(
        ExistingHeliosDeployment.newBuilder().heliosClient(client).build())
        .build();
  }

  public static TemporaryJobs create(final String domain) {
    return builder().heliosDeployment(
        ExistingHeliosDeployment.newBuilder().domain(domain).build())
        .build();
  }

  public static TemporaryJobs createFromProfile(final String profile) {
    return builder(profile).build();
  }

  @Override
  public Statement apply(final Statement base, final Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        before();
        try {
          perform(base);
        } finally {
          after();
        }
      }
    };
  }

  private void perform(final Statement base) throws InterruptedException {
    // Run the actual test on a thread
    final Future<Object> future = executor.submit(new Callable<Object>() {
      @Override
      public Object call() throws Exception {
        try {
          base.evaluate();
        } catch (MultipleFailureException e) {
          // Log the stack trace for each exception in the MultipleFailureException, because
          // stack traces won't be logged when this is caught and logged higher in the call stack.
          final List<Throwable> failures = e.getFailures();
          log.error(format("MultipleFailureException contains %d failures:", failures.size()));
          for (int i = 0; i < failures.size(); i++) {
            log.error(format("MultipleFailureException %d:", i), failures.get(i));
          }
          throw Throwables.propagate(e);
        } catch (Throwable throwable) {
          Throwables.propagateIfPossible(throwable, Exception.class);
          throw Throwables.propagate(throwable);
        }
        return null;
      }
    });

    // Monitor jobs while test is running
    while (!future.isDone()) {
      Thread.sleep(JOB_HEALTH_CHECK_INTERVAL_MILLIS);
      verifyJobsHealthy();
    }

    // Rethrow test failure, if any
    try {
      future.get();
    } catch (ExecutionException e) {
      final Throwable cause = (e.getCause() == null) ? e : e.getCause();
      throw Throwables.propagate(cause);
    }
  }

  private void verifyJobsHealthy() throws AssertionError {
    for (final TemporaryJob job : jobs) {
      Jobs.verifyHealthy(job.job(), client);
    }
  }

  String jobPrefix() {
    return jobPrefix;
  }

  public HeliosClient client() {
    return client;
  }

  public List<AssertionError> undeploy(final Job job, final List<String> hosts) {
    return undeployer.undeploy(job, hosts);
  }

  public static Builder builder() {
    return builder((String) null);
  }

  public static Builder builder(final String profile) {
    return builder(profile, System.getenv());
  }

  static Builder builder(final Map<String, String> env) {
    return builder(null, env);
  }

  static Builder builder(final String profile, final Map<String, String> env) {
    return new Builder(profile, HeliosConfig.loadConfig("helios-testing"), env);
  }

  public static class Builder {

    private final Map<String, String> env;
    private final Config config;
    private Prober prober = DEFAULT_PROBER;
    private Deployer deployer;
    private Undeployer undeployer;
    private String hostFilter;
    private HeliosDeployment heliosDeployment;
    // TODO (dxia) This random string logic is duplicated in JobPrefixFile
    private String jobPrefix = String.format(
        "tmp-%s-%s", new SimpleDateFormat("yyyyMMdd").format(new Date()),
        toHexString(ThreadLocalRandom.current().nextInt()));
    private String jobDeployedMessageFormat;
    private HostPickingStrategy hostPickingStrategy = HostPickingStrategies.randomOneHost();
    private long deployTimeoutMillis = DEFAULT_DEPLOY_TIMEOUT_MILLIS;

    Builder(final String profile, final Config rootConfig, final Map<String, String> env) {
      this.env = env;

      if (profile == null) {
        this.config = HeliosConfig.getDefaultProfile(
            HELIOS_TESTING_PROFILE, HELIOS_TESTING_PROFILES, rootConfig);
      } else {
        this.config = HeliosConfig.getProfile(HELIOS_TESTING_PROFILES, profile, rootConfig);
      }

      ExistingHeliosDeployment existingHeliosDeployment = null;

      if (this.config.hasPath("jobDeployedMessageFormat")) {
        jobDeployedMessageFormat(this.config.getString("jobDeployedMessageFormat"));
      }
      if (this.config.hasPath("hostFilter")) {
        hostFilter(this.config.getString("hostFilter"));
      }

      if (this.config.hasPath("domain")) {
        existingHeliosDeployment = ExistingHeliosDeployment.newBuilder()
            .domain(this.config.getString("domain"))
            .build();
      } else if (this.config.hasPath("endpoints")) {
        existingHeliosDeployment = ExistingHeliosDeployment.newBuilder()
            .endpointStrings(getListByKey("endpoints", config))
            .build();
      }

      if (this.config.hasPath("hostPickingStrategy")) {
        processHostPickingStrategy();
      }
      if (this.config.hasPath("deployTimeoutMillis")) {
        deployTimeoutMillis(this.config.getLong("deployTimeoutMillis"));
      }

      heliosDeployment = existingHeliosDeployment;
    }

    private void processHostPickingStrategy() {
      final String value = this.config.getString("hostPickingStrategy");
      if ("random".equals(value)) {
        hostPickingStrategy(HostPickingStrategies.random());

      } else if ("onerandom".equals(value)) {
        hostPickingStrategy(HostPickingStrategies.randomOneHost());

      } else if ("deterministic".equals(value)) {
        verifyHasStrategyKey(value);
        hostPickingStrategy(HostPickingStrategies.deterministic(
            this.config.getString("hostPickingStrategyKey")));

      } else if ("onedeterministic".equals(value)) {
        verifyHasStrategyKey(value);
        hostPickingStrategy(HostPickingStrategies.deterministicOneHost(
            this.config.getString("hostPickingStrategyKey")));

      } else {
        throw new RuntimeException("The hostPickingStrategy " + value + " is not valid. "
          + "Valid values are [random, onerandom, deterministic, onedeterministic] and the "
          + "deterministic variants require a string value hostPickingStrategyKey to be set "
          + "which is used to seed the random number generator, so can be any string.");
      }
    }

    private void verifyHasStrategyKey(final String value) {
      if (!this.config.hasPath("hostPickingStrategyKey")) {
        throw new RuntimeException("host picking strategy [" + value + "] selected but no "
            + "value for hostPickingStrategyKey which is used to seed the random number generator");
      }
    }

    public Builder heliosDeployment(final HeliosDeployment heliosDeployment) {
      this.heliosDeployment = heliosDeployment;
      return this;
    }

    public Builder hostPickingStrategy(final HostPickingStrategy strategy) {
      this.hostPickingStrategy = strategy;
      return this;
    }

    public Builder jobDeployedMessageFormat(final String jobLinkFormat) {
      this.jobDeployedMessageFormat = jobLinkFormat;
      return this;
    }

    public Builder prober(final Prober prober) {
      this.prober = prober;
      return this;
    }

    Builder deployer(final Deployer deployer) {
      this.deployer = deployer;
      return this;
    }

    @VisibleForTesting
    Builder undeployer(final Undeployer undeployer) {
      this.undeployer = undeployer;
      return this;
    }

    public Builder hostFilter(final String hostFilter) {
      this.hostFilter = hostFilter;
      return this;
    }

    public Builder jobPrefix(final String jobPrefix) {
      this.jobPrefix = jobPrefix;
      return this;
    }

    public Builder deployTimeoutMillis(final long timeout) {
      this.deployTimeoutMillis = timeout;
      return this;
    }

    public TemporaryJobs build() {
      return new TemporaryJobs(this, config);
    }
  }
}
