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
package co.cask.tracker;

import co.cask.cdap.api.service.http.AbstractHttpServiceHandler;
import co.cask.cdap.api.service.http.HttpServiceContext;
import co.cask.cdap.api.service.http.HttpServiceRequest;
import co.cask.cdap.api.service.http.HttpServiceResponder;
import co.cask.tracker.entity.AuditHistogramResult;
import co.cask.tracker.entity.AuditMetricsCube;
import co.cask.tracker.entity.EntityLatestTimestampTable;
import co.cask.tracker.entity.TimeSinceResult;
import co.cask.tracker.entity.TopApplicationsResult;
import co.cask.tracker.entity.TopProgramsResult;
import com.google.common.base.Strings;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;


/**
 * This class handles requests to the AuditLog API.
 */
public final class AuditMetricsHandler extends AbstractHttpServiceHandler {
  private AuditMetricsCube auditMetricsCube;
  private EntityLatestTimestampTable entityLatestTimestampTable;
  private String namespace;

  // Error messages
  private static final String LIMIT_INVALID_MESSAGE = "Limit cannot be negative or zero.";
  private static final String STARTTIME_GREATER_THAN_ENDTIME = "Start time cannot be greater than end time.";
  private static final String SPECIFY_ENTITY_NAME_AND_TYPE = "Entity Name and Entity Type must be specified.";
  private static final String INVALID_TOP_ENTITY_REQUEST = "Invalid request for top entities: path not recognized";

  @Override
  public void initialize(HttpServiceContext context) throws Exception {
    super.initialize(context);
    namespace = context.getNamespace();
    auditMetricsCube = context.getDataset(TrackerApp.AUDIT_METRICS_DATASET_NAME);
    entityLatestTimestampTable = context.getDataset(TrackerApp.ENTITY_LATEST_TIMESTAMP_DATASET_NAME);

  }

  @Path("v1/auditmetrics/top-entities/{entity-name}")
  @GET
  public void topNEntity(HttpServiceRequest request, HttpServiceResponder responder,
                               @PathParam("entity-name") String topEntity,
                               @QueryParam("limit") @DefaultValue("5") int limit,
                               @QueryParam("startTime") @DefaultValue("0") long startTime,
                               @QueryParam("endTime") @DefaultValue("0") long endTime,
                               @QueryParam("entityType") @DefaultValue("") String entityType,
                               @QueryParam("entityName") @DefaultValue("") String entityName) {
    if (!isLimitValid(limit)) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST.getCode(), LIMIT_INVALID_MESSAGE, StandardCharsets.UTF_8);
      return;
    }
    endTime = setEndTime(endTime);

    if (!isTimeFrameValid(startTime, endTime)) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST.getCode(), STARTTIME_GREATER_THAN_ENDTIME,
                           StandardCharsets.UTF_8);
      return;
    }

    switch (topEntity) {
      case "applications":
        List<TopApplicationsResult> appResult;
        if (isDatasetSpecified(entityType, entityName)) {
          appResult = auditMetricsCube.getTopNApplications(limit, startTime, endTime,
                                                           namespace, entityType, entityName);
        } else {
          appResult = auditMetricsCube.getTopNApplications(limit, startTime, endTime, namespace);
        }
        responder.sendJson(appResult);
        break;
      case "programs":
        List<TopProgramsResult> progResult;
        if (isDatasetSpecified(entityType, entityName)) {
          progResult = auditMetricsCube.getTopNPrograms(limit, startTime, endTime, namespace, entityType, entityName);
        } else {
          progResult = auditMetricsCube.getTopNPrograms(limit, startTime, endTime, namespace);
        }
        responder.sendJson(progResult);
        break;
      case "datasets":
        responder.sendJson(auditMetricsCube.getTopNDatasets(limit, startTime, endTime, namespace));
        break;
      default:
        responder.sendString(HttpResponseStatus.BAD_REQUEST.getCode(),
                             INVALID_TOP_ENTITY_REQUEST, StandardCharsets.UTF_8);
    }
  }

  @Path("v1/auditmetrics/time-since")
  @GET
  public void timeSince(HttpServiceRequest request, HttpServiceResponder responder,
                        @QueryParam("entityType") String entityType, @QueryParam("entityName") String entityName) {
    if (Strings.isNullOrEmpty(entityType) || Strings.isNullOrEmpty(entityName)) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST.getCode(),
                           SPECIFY_ENTITY_NAME_AND_TYPE, StandardCharsets.UTF_8);
      return;
    }
    TimeSinceResult result = entityLatestTimestampTable.read(namespace, entityType, entityName);
    responder.sendJson(result.getTimeSinceEvents());
  }

  @Path("v1/auditmetrics/audit-histogram")
  @GET
  public void auditLogHistogram(HttpServiceRequest request, HttpServiceResponder responder,
                                @QueryParam("startTime") @DefaultValue("0") long startTime,
                                @QueryParam("endTime") @DefaultValue("0") long endTime) {
    endTime = setEndTime(endTime);

    if (!isTimeFrameValid(startTime, endTime)) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST.getCode(), STARTTIME_GREATER_THAN_ENDTIME,
                           StandardCharsets.UTF_8);
      return;
    }
    AuditHistogramResult result = auditMetricsCube.getAuditHistogram(startTime, endTime, namespace);
    responder.sendJson(result);
  }


  private boolean isLimitValid (int limit) {
    return (limit > 0);
  }

  private boolean isTimeFrameValid (long startTime, long endTime) {
    return (startTime < endTime);
  }

  private boolean isDatasetSpecified (String entityType, String entityName) {
    return (!Strings.isNullOrEmpty(entityType) && !Strings.isNullOrEmpty(entityName));
  }

  // If endTime received "0" by default, change it to the current time
  private long setEndTime(long endTime) {
    if (endTime == 0) {
      return (System.currentTimeMillis() / 1000);
    }
    return endTime;
  }

}
