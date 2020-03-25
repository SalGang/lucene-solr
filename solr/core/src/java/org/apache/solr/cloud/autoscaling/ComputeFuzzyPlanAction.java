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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.cloud.autoscaling.AutoScalingConfig;
import org.apache.solr.client.solrj.cloud.autoscaling.NoneSuggester;
import org.apache.solr.client.solrj.cloud.autoscaling.Policy;
import org.apache.solr.client.solrj.cloud.autoscaling.PolicyHelper;
import org.apache.solr.client.solrj.cloud.autoscaling.Suggester;
import org.apache.solr.client.solrj.cloud.autoscaling.UnsupportedSuggester;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.params.AutoScalingParams;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.util.Pair;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.SolrResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.cloud.autoscaling.TriggerEvent.NODE_NAMES;

/**
 * This class is responsible for using the configured policy and preferences
 * with the hints provided by the trigger event to compute the required cluster operations.
 * <p>
 * The cluster operations computed here are put into the {@link ActionContext}'s properties
 * with the key name "operations". The value is a List of SolrRequest objects.
 */
public class ComputeFuzzyPlanAction extends ComputePlanAction {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Override
  public void process(TriggerEvent event, ActionContext context) throws Exception {
    log.debug("-- processing event: {} with context properties: {}", event, context.getProperties());
    SolrCloudManager cloudManager = context.getCloudManager();
    try {
      AutoScalingConfig autoScalingConf = cloudManager.getDistribStateManager().getAutoScalingConfig();
      if (autoScalingConf.isEmpty()) {
        throw new Exception("Action: " + getName() + " executed but no policy is configured");
      }
      PolicyHelper.SessionWrapper sessionWrapper = PolicyHelper.getSession(cloudManager);
      Policy.Session session = sessionWrapper.get();
      ClusterState clusterState = cloudManager.getClusterStateProvider().getClusterState();
      if (log.isTraceEnabled()) {
        log.trace("-- session: {}", session);
        log.trace("-- state: {}", clusterState);
      }
      try {
        Suggester suggester = getSuggester(session, event, context, cloudManager);
        int maxOperations = getMaxNumOps(event, autoScalingConf, clusterState);
        int requestedOperations = getRequestedNumOps(event);
        if (requestedOperations > maxOperations) {
          log.warn("Requested number of operations {} higher than maximum {}, adjusting...",
              requestedOperations, maxOperations);
        }
        int opCount = 0;
        int opLimit = maxOperations;
        if (requestedOperations > 0) {
          opLimit = requestedOperations;
        }
        do {
          // computing changes in large clusters may take a long time
          if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("stopping - thread was interrupted");
          }
          SolrRequest operation = suggester.getSuggestion();
          opCount++;
          // prepare suggester for the next iteration
          if (suggester.getSession() != null) {
            session = suggester.getSession();
          }
          suggester = getSuggester(session, event, context, cloudManager);

          // break on first null op
          // unless a specific number of ops was requested
          // uncomment the following to log too many operations
          /*if (opCount > 10) {
            PolicyHelper.logState(cloudManager, initialSuggester);
          }*/

          if (operation == null) {
            if (requestedOperations < 0) {
              //uncomment the following to log zero operations
//              PolicyHelper.logState(cloudManager, initialSuggester);
              break;
            } else {
              log.info("Computed plan empty, remained " + (opCount - opLimit) + " requested ops to try.");
              continue;
            }
          }
          log.debug("Computed Plan: {}", operation.getParams());
          if (!collections.isEmpty()) {
            String coll = operation.getParams().get(CoreAdminParams.COLLECTION);

            boolean isMatch = false;

            for (Iterator<String> it = collections.iterator(); it.hasNext(); ) {
              String f = it.next();
              if (coll.startsWith(f)) {
                isMatch = true;
                break;
              }
            }

            if (!isMatch) {
              // discard an op that doesn't affect our collections
              log.debug("-- discarding due to collection={} not in {}", coll, collections);
              continue;
            }
          }
          Map<String, Object> props = context.getProperties();
          props.compute("operations", (k, v) -> {
            List<SolrRequest> operations = (List<SolrRequest>) v;
            if (operations == null) operations = new ArrayList<>();
            operations.add(operation);
            return operations;
          });
        } while (opCount < opLimit);
      } finally {
        releasePolicySession(sessionWrapper, session);
      }
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
          "Unexpected exception while processing event: " + event, e);
    }
  }
}
