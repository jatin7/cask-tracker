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

import co.cask.cdap.api.dataset.lib.CloseableIterator;
import co.cask.cdap.api.service.http.AbstractHttpServiceHandler;
import co.cask.cdap.api.service.http.HttpServiceContext;
import co.cask.cdap.api.service.http.HttpServiceRequest;
import co.cask.cdap.api.service.http.HttpServiceResponder;
import co.cask.cdap.proto.audit.AuditMessage;
import co.cask.tracker.entity.AuditLogResponse;
import co.cask.tracker.entity.AuditLogTable;
import co.cask.tracker.utils.TimeMathParser;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;


/**
 * This class handles requests to the AuditLog API.
 */
public final class AuditLogHandler extends AbstractHttpServiceHandler {
  private static final String DEFAULT_PAGE_SIZE = "10";
  // If we scan more than this + offset, we return early since the UI can't display that many anyway.
  private static final long MAX_RESULTS_TO_SCAN = 100;

  private AuditLogTable auditLogTable;
  private String namespace;

  // Error messages
  private static final String LIMIT_INVALID = "Limit cannot be negative or zero.";
  private static final String OFFSET_INVALID = "Offset cannot be negative.";
  private static final String STARTTIME_GREATER_THAN_ENDTIME = "Start time cannot be greater than end time.";
  private static final String INVALID_TIME_FORMAT = "startTime or endTime was not in the correct format. " +
                                                    "Use unix timestamps or date math such as now-1h.";

  @Override
  public void initialize(HttpServiceContext context) throws Exception {
    super.initialize(context);
    namespace = context.getNamespace();
    auditLogTable = context.getDataset(TrackerApp.AUDIT_LOG_DATASET_NAME);
  }

  @Path("auditlog/{type}/{name}")
  @GET
  public void query(HttpServiceRequest request, HttpServiceResponder responder,
                    @PathParam("type") String entityType,
                    @PathParam("name") String name,
                    @QueryParam("offset") int offset,
                    @QueryParam("limit") @DefaultValue(DEFAULT_PAGE_SIZE) int limit,
                    @QueryParam("startTime") @DefaultValue("0") String startTime,
                    @QueryParam("endTime") @DefaultValue("now") String endTime) {
    if (!isLimitValid(limit)) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST.getCode(), LIMIT_INVALID, StandardCharsets.UTF_8);
      return;
    }
    if (!isOffsetValid(offset)) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST.getCode(), OFFSET_INVALID, StandardCharsets.UTF_8);
      return;
    }
    long startTimeLong = parseTime(startTime);
    long endTimeLong = parseTime(endTime);
    if (!isTimeFormatValid(startTimeLong, endTimeLong)) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST.getCode(), INVALID_TIME_FORMAT, StandardCharsets.UTF_8);
      return;
    }
    if (!isTimeFrameValid(startTimeLong, endTimeLong)) {
      responder.sendString(HttpResponseStatus.BAD_REQUEST.getCode(),
                           STARTTIME_GREATER_THAN_ENDTIME, StandardCharsets.UTF_8);
      return;
    }
    List<AuditMessage> logList = new ArrayList<>();
    int totalResults = 0;
    AuditMessage message;
    CloseableIterator<AuditMessage> messageIter = auditLogTable.scan(namespace,
            entityType,
            name,
            startTimeLong,
            endTimeLong);
    try {
      // First skip to the offset
      if (offset > 0) {
        while (totalResults < offset && (message = messageIter.next()) != null) {
          totalResults++;
        }
      }
      while ((message = messageIter.next()) != null) {
        totalResults++;
        if (totalResults <= (limit + offset)) {
          logList.add(message);
        }
        // End early if there are too many results to scan.
        if (totalResults >= (MAX_RESULTS_TO_SCAN + offset)) {
          break;
        }
      }
    } catch (NoSuchElementException e) {
      //no-op
    } finally {
      messageIter.close();
    }

    AuditLogResponse resp = new AuditLogResponse(totalResults, logList, offset);
    responder.sendJson(200, resp);
  }

  private boolean isLimitValid (int limit) {
    return (limit > 0);
  }

  private boolean isOffsetValid(int offset) {
    return (offset >= 0);
  }

  private boolean isTimeFrameValid (long startTime, long endTime) {
    return (startTime < endTime);
  }

  private boolean isTimeFormatValid (long startTime, long endTime) {
    return (startTime != -1 && endTime != -1);
  }

  private long parseTime(String time) {
    long timeStamp;
    if (time != null) {
      try {
        timeStamp = TimeMathParser.parseTime(time, TimeUnit.SECONDS);
      } catch (IllegalArgumentException e) {
        timeStamp = -1;
      }
    } else {
      timeStamp = -1;
    }
    return timeStamp;
  }
}

