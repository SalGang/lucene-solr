/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud.autoscaling;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.cloud.autoscaling.AutoScalingConfig;
import org.apache.solr.client.solrj.cloud.autoscaling.NoneSuggester;
import org.apache.solr.client.solrj.cloud.autoscaling.Policy;
import org.apache.solr.client.solrj.cloud.autoscaling.PolicyHelper;
import org.apache.solr.client.solrj.cloud.autoscaling.Suggester;
import org.apache.solr.client.solrj.cloud.autoscaling.UnsupportedSuggester;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.params.AutoScalingParams;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.util.Pair;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.SolrResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.rmi.runtime.Log;

import static org.apache.solr.cloud.autoscaling.TriggerEvent.NODE_NAMES;

/**
 * This class is responsible for using the configured policy and preferences
 * with the hints provided by the trigger event to compute the required cluster operations.
 * <p>
 * The cluster operations computed here are put into the {@link ActionContext}'s properties
 * with the key name "operations". The value is a List of SolrRequest objects.
 */
public class AddReplicasPlanAction extends TriggerActionBase {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  HashMap<String, Integer> map = new HashMap<>();

  public AddReplicasPlanAction() {
    super();
    TriggerUtils.validProperties(validProperties, "collections");
  }


  @Override
  public void configure(SolrResourceLoader loader, SolrCloudManager cloudManager, Map<String, Object> properties) throws TriggerValidationException {
    super.configure(loader, cloudManager, properties);
    String colString = (String) properties.get("collections");

    if (colString != null && !colString.isEmpty()) {
      Set<String> collections = new HashSet<>(StrUtils.splitSmart(colString, ','));

      for (String f : collections) {
        try {
          int indexOfReplicasNumber = f.indexOf("{");

          if (indexOfReplicasNumber == -1) {
            continue;
          }

          int replicas = Integer.parseInt(f.substring(indexOfReplicasNumber + 1, indexOfReplicasNumber + 2));
          String collectionName = f.replace("{" + replicas + "}", "");

          map.put(collectionName, replicas);
          log.info("Custom {} added {}", getName(), collectionName);
        }
        catch (Exception ex){
          log.error("Failed to parse collection with name {}", f);
        }
      }
    }
  }

  @Override
  public void process(TriggerEvent event, ActionContext context) throws Exception {
    log.info("-- processing event: {} with context properties: {}", event, context.getProperties());
    SolrCloudManager cloudManager = context.getCloudManager();
    try {
      log.info("Custom action plan started: {}", getName());
      PolicyHelper.SessionWrapper sessionWrapper = PolicyHelper.getSession(cloudManager);
      Policy.Session session = sessionWrapper.get();
      ClusterState clusterState = cloudManager.getClusterStateProvider().getClusterState();
      if (log.isTraceEnabled()) {
        log.trace("-- session: {}", session);
        log.trace("-- state: {}", clusterState);
      }
      try {

        Set<String> collections = map.keySet();

        log.info("Custom action plan {} collections with count {}",getName(), collections.size());

        for (String collection : collections) {
          log.info(collection);
        }

        Map<String, DocCollection> collectionsMap = clusterState.getCollectionsMap();

        for (String coll : collections) {

          Optional<String> first = collectionsMap.keySet().stream().filter(x -> x.startsWith(coll)).findFirst();

          if (first.isPresent()) {
            log.info("Custom action {} find collection {}", getName(), first.get());

            DocCollection var = collectionsMap.get(first.get());

            log.info("Custom action {} find collection map {}", getName(), var.toString());

            Collection<Slice> slices = var.getSlices();

            log.info("Custom action {} find slices with size {}", getName(), slices.size());

            for (Slice slice : slices) {
              try {
                int replicasToAdd = map.get(coll) - slice.getReplicas().size();

                for (int i = 0; i < replicasToAdd; i++) {

                  log.info("Custom action try adding operation for shard {}", slice.getName());
                  CollectionAdminRequest.AddReplica addReplica = CollectionAdminRequest.AddReplica.addReplicaToShard(first.get(), slice.getName());

                  Map<String, Object> props = context.getProperties();

                  props.compute("operations", (k, v) -> {
                    List<SolrRequest> operations = (List<SolrRequest>) v;
                    if (operations == null) operations = new ArrayList<>();
                    operations.add(addReplica);
                    return operations;
                  });
                  log.info("Custom action {} added operation with parameters {}", getName(), addReplica.toString());
                }
              } catch (Exception ex) {
                log.error("Custom action error", ex);
                break;
              }
            }
          }
          else {
            log.warn("Custom action {} failed find first", getName());
          }
        }
      } finally {
        releasePolicySession(sessionWrapper, session);
      }
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
          "Unexpected exception while processing custom event: " + event, e);
    }
  }

  protected void releasePolicySession(PolicyHelper.SessionWrapper sessionWrapper, Policy.Session session) {
    sessionWrapper.returnSession(session);
    sessionWrapper.release();
  }
}