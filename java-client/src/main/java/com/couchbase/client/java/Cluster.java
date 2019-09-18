/*
 * Copyright (c) 2018 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.java;

import com.couchbase.client.core.Core;
import com.couchbase.client.core.annotation.Stability;
import com.couchbase.client.core.env.Authenticator;
import com.couchbase.client.core.env.OwnedSupplier;
import com.couchbase.client.core.env.PasswordAuthenticator;
import com.couchbase.client.core.env.SeedNode;
import com.couchbase.client.core.msg.search.SearchRequest;
import com.couchbase.client.java.analytics.AnalyticsOptions;
import com.couchbase.client.java.analytics.AnalyticsResult;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.manager.analytics.AnalyticsIndexManager;
import com.couchbase.client.java.manager.bucket.BucketManager;
import com.couchbase.client.java.manager.query.QueryIndexManager;
import com.couchbase.client.java.manager.search.SearchIndexManager;
import com.couchbase.client.java.manager.user.UserManager;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.search.SearchOptions;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.result.SearchResult;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

import static com.couchbase.client.java.AsyncCluster.extractClusterEnvironment;
import static com.couchbase.client.java.AsyncCluster.seedNodesFromConnectionString;
import static com.couchbase.client.java.AsyncUtils.block;
import static com.couchbase.client.java.ClusterOptions.clusterOptions;
import static com.couchbase.client.java.ReactiveCluster.DEFAULT_ANALYTICS_OPTIONS;
import static com.couchbase.client.java.ReactiveCluster.DEFAULT_QUERY_OPTIONS;
import static com.couchbase.client.java.ReactiveCluster.DEFAULT_SEARCH_OPTIONS;

/**
 * The {@link Cluster} is the main entry point when connecting to a Couchbase cluster.
 */
public class Cluster {

  /**
   * Holds the underlying async cluster reference.
   */
  private final AsyncCluster asyncCluster;

  /**
   * Holds the adjacent reactive cluster reference.
   */
  private final ReactiveCluster reactiveCluster;

  /**
   * Holds the index manager.
   */
  private final SearchIndexManager searchIndexManager;


  private final UserManager userManager;

  private final BucketManager bucketManager;

  private final QueryIndexManager queryIndexManager;

  private final AnalyticsIndexManager analyticsIndexManager;

  /**
   * Connect to a Couchbase cluster with a username and a password as credentials.
   *
   * @param connectionString connection string used to locate the Couchbase cluster.
   * @param username the name of the user with appropriate permissions on the cluster.
   * @param password the password of the user with appropriate permissions on the cluster.
   * @return if properly connected, returns a {@link Cluster}.
   */
  public static Cluster connect(final String connectionString, final String username, final String password) {
    return connect(connectionString, clusterOptions(PasswordAuthenticator.create(username, password)));
  }

  /**
   * Connect to a Couchbase cluster with custom {@link Authenticator}.
   *
   * @param connectionString connection string used to locate the Couchbase cluster.
   * @param options custom options when creating the cluster.
   * @return if properly connected, returns a {@link Cluster}.
   */
  public static Cluster connect(final String connectionString, final ClusterOptions options) {
    ClusterOptions.Built opts = options.build();
    Supplier<ClusterEnvironment> environmentSupplier = extractClusterEnvironment(connectionString, opts);
    Set<SeedNode> seedNodes = seedNodesFromConnectionString(connectionString, environmentSupplier.get());
    Cluster cluster = new Cluster(environmentSupplier, opts.authenticator(), seedNodes);
    cluster.async().performGlobalConnect().block();
    return cluster;
  }

  /**
   * Creates a new cluster from a {@link ClusterEnvironment}.
   *
   * @param environment the environment to use for this cluster.
   */
  private Cluster(final Supplier<ClusterEnvironment> environment, final Authenticator authenticator, Set<SeedNode> seedNodes) {
    this.asyncCluster = new AsyncCluster(environment, authenticator, seedNodes);
    this.reactiveCluster = new ReactiveCluster(asyncCluster);
    this.searchIndexManager = new SearchIndexManager(asyncCluster.searchIndexes());
    this.userManager = new UserManager(asyncCluster.users());
    this.bucketManager = new BucketManager(asyncCluster.buckets());
    this.queryIndexManager = new QueryIndexManager(asyncCluster.queryIndexes());
    this.analyticsIndexManager = new AnalyticsIndexManager(this);
  }

  /**
   * Provides access to the underlying {@link AsyncCluster}.
   */
  public AsyncCluster async() {
    return asyncCluster;
  }

  /**
   * Provides access to the related {@link ReactiveCluster}.
   */
  public ReactiveCluster reactive() {
    return reactiveCluster;
  }

  /**
   * Provides access to the underlying {@link Core}.
   *
   * <p>This is advanced API, use with care!</p>
   */
  @Stability.Volatile
  public Core core() {
    return asyncCluster.core();
  }

  /**
   * Provides access to the user management services.
   */
  @Stability.Volatile
  public UserManager users() {
    return userManager;
  }

  /**
   * Provides access to the bucket management services.
   */
  @Stability.Volatile
  public BucketManager buckets() {
    return bucketManager;
  }

  /**
   * Provides access to the Analytics index management services.
   */
  @Stability.Volatile
  public AnalyticsIndexManager analyticsIndexes() {
    return analyticsIndexManager;
  }

  /**
   * Provides access to the N1QL index management services.
   */
  @Stability.Volatile
  public QueryIndexManager queryIndexes() {
    return queryIndexManager;
  }

  /**
   * Provides access to the Full Text Search index management services.
   */
  @Stability.Volatile
  public SearchIndexManager searchIndexes() {
    return searchIndexManager;
  }

  /**
   * Provides access to the configured {@link ClusterEnvironment} for this cluster.
   */
  public ClusterEnvironment environment() {
    return asyncCluster.environment();
  }

  /**
   * Performs a N1QL query with default {@link QueryOptions}.
   *
   * @param statement the N1QL query statement as a raw string.
   * @return the {@link QueryResult} once the response arrives successfully.
   */
  public QueryResult query(final String statement) {
    return query(statement, DEFAULT_QUERY_OPTIONS);
  }

  /**
   * Performs a N1QL query with custom {@link QueryOptions}.
   *
   * @param statement the N1QL query statement as a raw string.
   * @param options the custom options for this query.
   * @return the {@link QueryResult} once the response arrives successfully.
   */
  public QueryResult query(final String statement, final QueryOptions options) {
    return block(async().query(statement, options));
  }

  /**
   * Performs an analytics query with default {@link AnalyticsOptions}.
   *
   * @param statement the query statement as a raw string.
   * @return the {@link AnalyticsResult} once the response arrives successfully.
   */
  public AnalyticsResult analyticsQuery(final String statement) {
    return analyticsQuery(statement, DEFAULT_ANALYTICS_OPTIONS);
  }

  /**
   * Performs an analytics query with custom {@link AnalyticsOptions}.
   *
   * @param statement the query statement as a raw string.
   * @param options the custom options for this query.
   * @return the {@link AnalyticsResult} once the response arrives successfully.
   */
  public AnalyticsResult analyticsQuery(final String statement, final AnalyticsOptions options) {
    return block(async().analyticsQuery(statement, options));
  }

  /**
   * Performs a Full Text Search (FTS) query with default {@link SearchOptions}.
   *
   * @param query the query, in the form of a {@link SearchQuery}
   * @return the {@link SearchRequest} once the response arrives successfully.
   */
  public SearchResult searchQuery(final SearchQuery query) {
    return searchQuery(query, DEFAULT_SEARCH_OPTIONS);
  }

  /**
   * Performs a Full Text Search (FTS) query with custom {@link SearchOptions}.
   *
   * @param query the query, in the form of a {@link SearchQuery}
   * @param options the custom options for this query.
   * @return the {@link SearchRequest} once the response arrives successfully.
   */
  public SearchResult searchQuery(final SearchQuery query, final SearchOptions options) {
    return block(asyncCluster.searchQuery(query, options));
  }

  /**
   * Opens a {@link Bucket} with the given name.
   *
   * @param name the name of the bucket to open.
   * @return a {@link Bucket} once opened.
   */
  public Bucket bucket(final String name) {
    AsyncBucket b = block(asyncCluster.bucket(name));
    return new Bucket(b);
  }

  /**
   * Performs a non-reversible shutdown of this {@link Cluster}.
   */
  public void shutdown() {
    block(asyncCluster.shutdown());
  }

  /**
   * Performs a non-reversible shutdown of this {@link Cluster}.
   *
   * @param timeout overriding the default disconnect timeout if needed.
   */
  public void shutdown(final Duration timeout) {
    block(asyncCluster.shutdown(timeout));
  }

}
