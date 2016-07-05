/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.tracker.entity;

import co.cask.cdap.api.dataset.DatasetSpecification;
import co.cask.cdap.api.dataset.lib.AbstractDataset;
import co.cask.cdap.api.dataset.lib.cube.AggregationFunction;
import co.cask.cdap.api.dataset.lib.cube.Cube;
import co.cask.cdap.api.dataset.lib.cube.CubeFact;
import co.cask.cdap.api.dataset.lib.cube.CubeQuery;
import co.cask.cdap.api.dataset.lib.cube.MeasureType;
import co.cask.cdap.api.dataset.lib.cube.TimeSeries;
import co.cask.cdap.api.dataset.lib.cube.TimeValue;
import co.cask.cdap.api.dataset.module.EmbeddedDataset;
import co.cask.cdap.proto.audit.AuditMessage;
import co.cask.cdap.proto.audit.AuditType;
import co.cask.cdap.proto.audit.payload.access.AccessPayload;
import co.cask.cdap.proto.audit.payload.access.AccessType;
import co.cask.cdap.proto.element.EntityType;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.NamespacedId;
import co.cask.tracker.utils.EntityIdHelper;

import com.google.common.base.Strings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.TimeUnit;

/**
 * An OLAP Cube to store metrics about the AuditLog.
 */
public class AuditMetricsCube extends AbstractDataset {
  private final Cube auditMetrics;

  private enum Bucket {
    DAY(TimeUnit.DAYS),
    HOUR(TimeUnit.HOURS);

    private final TimeUnit timeUnit;

    Bucket(TimeUnit timeUnit) {
      this.timeUnit = timeUnit;
    }

    public long getResolutionsSeconds() {
      return timeUnit.toSeconds(1L);
    }
  }

  public AuditMetricsCube(DatasetSpecification spec,
                          @EmbeddedDataset("auditMetrics") Cube auditMetrics) {
    super(spec.getName(), auditMetrics);
    this.auditMetrics = auditMetrics;
  }

  /**
   * Updates cube metrics based on information in the audit message
   *
   * @param auditMessage the message to update the stats for
   * @throws IOException if for some reason, it cannot find the name of the entity
   */
  public void write(AuditMessage auditMessage) throws IOException {
    EntityId entityId = auditMessage.getEntityId();
    if (!(entityId instanceof NamespacedId)) {
      throw
        new IllegalStateException(String.format("Entity '%s' does not have a namespace " +
                                                  "and was not written to AuditMetricsCube",
                                                entityId));
    }
    String namespace = ((NamespacedId) entityId).getNamespace();
    EntityType entityType = entityId.getEntity();
    String type = entityType.name().toLowerCase();
    String name = EntityIdHelper.getEntityName(entityId);

    long ts = System.currentTimeMillis() / 1000;
    CubeFact fact = new CubeFact(ts);

    fact.addDimensionValue("namespace", namespace);
    fact.addDimensionValue("entity_type", type);
    fact.addDimensionValue("entity_name", name);
    fact.addDimensionValue("audit_type", auditMessage.getType().name().toLowerCase());
    if (auditMessage.getPayload() instanceof AccessPayload) {
      AccessPayload accessPayload = ((AccessPayload) auditMessage.getPayload());
      String programName = EntityIdHelper.getEntityName(accessPayload.getAccessor());
      String appName = EntityIdHelper.getApplicationName(accessPayload.getAccessor());
      String programType = accessPayload.getAccessor().getEntity().name().toLowerCase();
      if (appName.length() != 0) {
        fact.addDimensionValue("app_name", appName);
      }
      fact.addDimensionValue("program_name", programName);
      fact.addDimensionValue("program_type", programType);

      fact.addMeasurement(accessPayload.getAccessType().name().toLowerCase(), MeasureType.COUNTER, 1L);
    }
    fact.addMeasurement("count", MeasureType.COUNTER, 1L);

    auditMetrics.add(fact);
  }

  /**
   * Returns the top N datasets with the most audit messages
   *
   * @return A list of entities and their stats sorted in DESC order by count
   */
  public List<TopDatasetsResult> getTopNDatasets(int topN, long startTime, long endTime, String namespace) {
    CubeQuery datasetQuery = CubeQuery.builder()
      .select()
      .measurement(AccessType.READ.name().toLowerCase(), AggregationFunction.SUM)
      .measurement(AccessType.WRITE.name().toLowerCase(), AggregationFunction.SUM)
      .from()
      .resolution(TimeUnit.DAYS.toSeconds(365L), TimeUnit.SECONDS)
      .where()
      .dimension("namespace", namespace)
      .dimension("entity_type", EntityType.DATASET.name().toLowerCase())
      .dimension("audit_type", AuditType.ACCESS.name().toLowerCase())
      .timeRange(startTime, endTime)
      .groupBy()
      .dimension("entity_name")
      .limit(1000)
      .build();

    CubeQuery streamQuery = CubeQuery.builder()
      .select()
      .measurement(AccessType.READ.name().toLowerCase(), AggregationFunction.SUM)
      .measurement(AccessType.WRITE.name().toLowerCase(), AggregationFunction.SUM)
      .from()
      .resolution(TimeUnit.DAYS.toSeconds(365L), TimeUnit.SECONDS)
      .where()
      .dimension("namespace", namespace)
      .dimension("entity_type", EntityType.STREAM.name().toLowerCase())
      .dimension("audit_type", AuditType.ACCESS.name().toLowerCase())
      .timeRange(startTime, endTime)
      .groupBy()
      .dimension("entity_name")
      .limit(1000)
      .build();
    Map<String, TopDatasetsResult> auditStats = transformTopNDatasetResult(auditMetrics.query(datasetQuery),
                                                                           auditMetrics.query(streamQuery));
    List<TopDatasetsResult> resultList = new ArrayList<>(auditStats.values());
    Collections.sort(resultList);
    return (topN >= resultList.size()) ? resultList : resultList.subList(0, topN);
  }

  private Map<String, TopDatasetsResult> transformTopNDatasetResult(Collection<TimeSeries> datasetResult,
                                                                    Collection<TimeSeries> streamResults) {
    HashMap<String, TopDatasetsResult> resultsMap = new HashMap<>();
    for (TimeSeries t : datasetResult) {
      String entityName = t.getDimensionValues().get("entity_name");
      String key = getKey(entityName, "dataset");
      if (!resultsMap.containsKey(key)) {
        resultsMap.put(key, new TopDatasetsResult(entityName, "dataset"));
      }
      TopDatasetsResult result = resultsMap.get(key);
      if (t.getMeasureName().equals("read")) {
        result.setRead(t.getTimeValues().get(0).getValue());
      } else if (t.getMeasureName().equals("write")) {
        result.setWrite(t.getTimeValues().get(0).getValue());
      }
    }

    for (TimeSeries t : streamResults) {
      String entityName = t.getDimensionValues().get("entity_name");
      String key = getKey(entityName, "stream");
      if (!resultsMap.containsKey(key)) {
        resultsMap.put(key, new TopDatasetsResult(entityName, "stream"));
      }
      TopDatasetsResult result = resultsMap.get(key);
      if (t.getMeasureName().equals("read")) {
        result.setRead(t.getTimeValues().get(0).getValue());
      } else if (t.getMeasureName().equals("write")) {
        result.setWrite(t.getTimeValues().get(0).getValue());
      }
    }
    return resultsMap;
  }

  public List<TopProgramsResult> getTopNPrograms(int topN, long startTime, long endTime, String namespace) {
    CubeQuery programQuery = CubeQuery.builder()
      .select()
      .measurement(AccessType.READ.name().toLowerCase(), AggregationFunction.SUM)
      .measurement(AccessType.WRITE.name().toLowerCase(), AggregationFunction.SUM)
      .from()
      .resolution(TimeUnit.DAYS.toSeconds(365L), TimeUnit.SECONDS)
      .where()
      .dimension("namespace", namespace)
      .dimension("audit_type", AuditType.ACCESS.name().toLowerCase())
      .timeRange(startTime, endTime)
      .groupBy()
      .dimension("program_name")
      .dimension("app_name")
      .dimension("program_type")
      .limit(1000)
      .build();
    Map<String, TopProgramsResult> auditStats = transformTopNProgramResult(auditMetrics.query(programQuery));
    List<TopProgramsResult> resultList = new ArrayList<>(auditStats.values());
    Collections.sort(resultList);
    return (topN >= resultList.size()) ? resultList : resultList.subList(0, topN);
  }

  public List<TopProgramsResult> getTopNPrograms(int topN, long startTime, long endTime,
                                                 String namespace, String entityType, String entityName) {
    CubeQuery programQuery = CubeQuery.builder()
      .select()
      .measurement(AccessType.READ.name().toLowerCase(), AggregationFunction.SUM)
      .measurement(AccessType.WRITE.name().toLowerCase(), AggregationFunction.SUM)
      .from()
      .resolution(TimeUnit.DAYS.toSeconds(365L), TimeUnit.SECONDS)
      .where()
      .dimension("namespace", namespace)
      .dimension("entity_name", entityName)
      .dimension("entity_type", entityType)
      .dimension("audit_type", AuditType.ACCESS.name().toLowerCase())
      .timeRange(startTime, endTime)
      .groupBy()
      .dimension("program_name")
      .dimension("app_name")
      .dimension("program_type")
      .limit(1000)
      .build();
    Map<String, TopProgramsResult> auditStats = transformTopNProgramResult(auditMetrics.query(programQuery));
    List<TopProgramsResult> resultList = new ArrayList<>(auditStats.values());
    Collections.sort(resultList);
    return (topN >= resultList.size()) ? resultList : resultList.subList(0, topN);
  }

  private Map<String, TopProgramsResult> transformTopNProgramResult(Collection<TimeSeries> results) {
    HashMap<String, TopProgramsResult> resultsMap = new HashMap<>();
    for (TimeSeries t : results) {
      String programName = t.getDimensionValues().get("program_name");
      if (Strings.isNullOrEmpty(programName)) {
        continue;
      }
      String appName = t.getDimensionValues().get("app_name");
      String programType = t.getDimensionValues().get("program_type");
      String key = getKey(appName, programName, programType);

      long value = t.getTimeValues().get(0).getValue();
      TopProgramsResult result = resultsMap.get(key);
      if (result == null) {
        resultsMap.put(key, new TopProgramsResult(programName, appName, programType, value));
      } else {
        result.increment(value);
      }
    }
    return resultsMap;
  }

  public List<TopApplicationsResult> getTopNApplications(int topN, long startTime, long endTime,
                                                         String namespace, String entityType, String entityName) {
    CubeQuery applicationQuery = CubeQuery.builder()
      .select()
      .measurement(AccessType.READ.name().toLowerCase(), AggregationFunction.SUM)
      .measurement(AccessType.WRITE.name().toLowerCase(), AggregationFunction.SUM)
      .from()
      .resolution(TimeUnit.DAYS.toSeconds(365L), TimeUnit.SECONDS)
      .where()
      .dimension("namespace", namespace)
      .dimension("entity_name", entityName)
      .dimension("entity_type", entityType)
      .dimension("audit_type", AuditType.ACCESS.name().toLowerCase())
      .timeRange(startTime, endTime)
      .groupBy()
      .dimension("app_name")
      .limit(1000)
      .build();
    Map<String, TopApplicationsResult> auditStats
      = transformTopNApplicationResult(auditMetrics.query(applicationQuery));
    List<TopApplicationsResult> resultList = new ArrayList<>(auditStats.values());
    Collections.sort(resultList);
    return (topN >= resultList.size()) ? resultList : resultList.subList(0, topN);
  }

  public List<TopApplicationsResult> getTopNApplications(int topN, long startTime, long endTime, String namespace) {
    CubeQuery applicationQuery = CubeQuery.builder()
      .select()
      .measurement(AccessType.READ.name().toLowerCase(), AggregationFunction.SUM)
      .measurement(AccessType.WRITE.name().toLowerCase(), AggregationFunction.SUM)
      .from()
      .resolution(TimeUnit.DAYS.toSeconds(365L), TimeUnit.SECONDS)
      .where()
      .dimension("audit_type", AuditType.ACCESS.name().toLowerCase())
      .dimension("namespace", namespace)
      .timeRange(startTime, endTime)
      .groupBy()
      .dimension("app_name")
      .limit(1000)
      .build();
    Map<String, TopApplicationsResult> auditStats
      = transformTopNApplicationResult(auditMetrics.query(applicationQuery));
    List<TopApplicationsResult> resultList = new ArrayList<>(auditStats.values());
    Collections.sort(resultList);
    return (topN >= resultList.size()) ? resultList : resultList.subList(0, topN);

  }


  private Map<String, TopApplicationsResult> transformTopNApplicationResult(Collection<TimeSeries> results) {
    HashMap<String, TopApplicationsResult> resultsMap = new HashMap<>();
    for (TimeSeries t : results) {
      String appName = t.getDimensionValues().get("app_name");
      if (Strings.isNullOrEmpty(appName)) {
        continue;
      }
      long value = t.getTimeValues().get(0).getValue();
      TopApplicationsResult result = resultsMap.get(appName);
      if (result == null) {
        resultsMap.put(appName, new TopApplicationsResult(appName, value));
      } else {
        result.increment(value);
      }
    }
    return resultsMap;
  }

  public AuditHistogramResult getAuditHistogram(long startTime, long endTime, String namespace) {
    Bucket resolution = getResolutionBucket(startTime, endTime);
    CubeQuery histogramQuery = CubeQuery.builder()
      .select()
      .measurement("count", AggregationFunction.SUM)
      .from()
      .resolution(resolution.getResolutionsSeconds(), TimeUnit.SECONDS)
      .where()
      .dimension("namespace", namespace)
      .timeRange(startTime, endTime)
      .limit(1000)
      .build();

    Collection<TimeSeries> results = auditMetrics.query(histogramQuery);
    TimeSeries t = results.iterator().next();
    List<TimeValue> timeValueList = t.getTimeValues();

    return new AuditHistogramResult(resolution.name(), timeValueList);
  }

  // This will be updated if we change how we select resolution.
  private Bucket getResolutionBucket(long startTime, long endTime) {
    if ((endTime - startTime) > TimeUnit.DAYS.toSeconds(7L)) {
      return Bucket.DAY;
    }
    return Bucket.HOUR;
  }

  private static String getKey(String s1, String s2, String s3) {
    return String.format("%s%s%s%s%s%s", Integer.toString(s1.length()), s1, Integer.toString(s2.length()), s2,
                         Integer.toString(s3.length()), s3);
  }

  private static String getKey(String s1, String s2) {
    return String.format("%s%s%s%s", Integer.toString(s1.length()), s1, Integer.toString(s2.length()), s2);
  }
}
