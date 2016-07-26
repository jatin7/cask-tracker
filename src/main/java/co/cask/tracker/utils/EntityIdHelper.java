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

package co.cask.tracker.utils;

import co.cask.cdap.proto.element.EntityType;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.ArtifactId;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.proto.id.DatasetModuleId;
import co.cask.cdap.proto.id.DatasetTypeId;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.FlowletId;
import co.cask.cdap.proto.id.FlowletQueueId;
import co.cask.cdap.proto.id.NotificationFeedId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.id.ProgramRunId;
import co.cask.cdap.proto.id.ScheduleId;
import co.cask.cdap.proto.id.StreamId;
import co.cask.cdap.proto.id.StreamViewId;
import co.cask.cdap.proto.id.SystemServiceId;

import java.io.IOException;

/**
 * A class with static methods to find name, parent app, and program type of an entity.
 */

public class EntityIdHelper {
  /*
   * Get the entity name of an entity
   */
  public static String getEntityName(EntityId entityId) {
    EntityType entityType = entityId.getEntity();
    String name;
    switch (entityType) {
      case APPLICATION:
        name = ((ApplicationId) entityId).getApplication();
        break;
      case ARTIFACT:
        name = ((ArtifactId) entityId).getArtifact(); //Changed to ArtifactID from NameSpacedArtifactID
        break;
      case DATASET:
        name = ((DatasetId) entityId).getDataset();
        break;
      case DATASET_MODULE:
        name = ((DatasetModuleId) entityId).getModule();
        break;
      case DATASET_TYPE:
        name = ((DatasetTypeId) entityId).getType();
        break;
      case FLOWLET:
        name = ((FlowletId) entityId).getFlowlet();
        break;
      case FLOWLET_QUEUE:
        name = ((FlowletQueueId) entityId).getQueue();
        break;
      case NOTIFICATION_FEED:
        name = ((NotificationFeedId) entityId).getFeed();
        break;
      case PROGRAM:
        name = ((ProgramId) entityId).getProgram();
        break;
      case PROGRAM_RUN:
        name = ((ProgramRunId) entityId).getProgram();
        break;
      case SCHEDULE:
        name = ((ScheduleId) entityId).getSchedule();
        break;
      case STREAM:
        name = ((StreamId) entityId).getStream();
        break;
      case STREAM_VIEW:
        name = ((StreamViewId) entityId).getView();
        break;
      case SYSTEM_SERVICE:
        name = ((SystemServiceId) entityId).getService();
        break;
      default:
        throw new IllegalStateException("Unknown entity type: " + entityType);
    }
    return name;
  }

  /*
   * Get the parent application name of an entity if it has one
   */
  public static String getParentApplicationName(EntityId entityId) throws IOException {
    EntityType entityType = entityId.getEntity();
    String name;
    switch (entityType) {
      case APPLICATION:
        name = ((ApplicationId) entityId).getApplication();
        break;
      case FLOWLET:
        name = ((FlowletId) entityId).getApplication();
        break;
      case FLOWLET_QUEUE:
        name = ((FlowletQueueId) entityId).getApplication();
        break;
      case PROGRAM:
        name = ((ProgramId) entityId).getApplication();
        break;
      case PROGRAM_RUN:
        name = ((ProgramRunId) entityId).getApplication();
        break;
      case SCHEDULE:
        name = ((ScheduleId) entityId).getApplication();
        break;
      default:
        name = "";
    }
    return name;
  }

  /*
   * Get the program type of an entity.
   */
  public static String getProgramType(EntityId entityId) throws IOException {
    if (entityId instanceof ProgramId) {
      return ((ProgramId) entityId).getType().name().toLowerCase();
    } else if (entityId instanceof ProgramRunId) {
      return ((ProgramRunId) entityId).getType().name().toLowerCase();
    }
    return "";
  }
}
