/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.mongodb.persistence.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.impl.persistence.cache.CachedEntityMatcher;
import org.flowable.common.engine.impl.persistence.entity.Entity;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.ExecutionQueryImpl;
import org.flowable.engine.impl.ProcessInstanceQueryImpl;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.persistence.entity.ExecutionEntityImpl;
import org.flowable.engine.impl.persistence.entity.data.ExecutionDataManager;
import org.flowable.engine.impl.persistence.entity.data.impl.cachematcher.ExecutionByProcessInstanceMatcher;
import org.flowable.engine.impl.persistence.entity.data.impl.cachematcher.ExecutionsByParentExecutionIdEntityMatcher;
import org.flowable.engine.impl.persistence.entity.data.impl.cachematcher.ExecutionsByProcessInstanceIdEntityMatcher;
import org.flowable.engine.impl.persistence.entity.data.impl.cachematcher.ExecutionsByRootProcessInstanceMatcher;
import org.flowable.engine.impl.persistence.entity.data.impl.cachematcher.ExecutionsWithSameRootProcessInstanceIdMatcher;
import org.flowable.engine.impl.persistence.entity.data.impl.cachematcher.InactiveExecutionsByProcInstMatcher;
import org.flowable.engine.impl.persistence.entity.data.impl.cachematcher.InactiveExecutionsInActivityAndProcInstMatcher;
import org.flowable.engine.impl.util.ProcessDefinitionUtil;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;

import com.mongodb.BasicDBObject;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;

/**
 * @author Joram Barrez
 */
public class MongoDbExecutionDataManager extends AbstractMongoDbDataManager<ExecutionEntity> implements ExecutionDataManager {
    
    public static final String COLLECTION_EXECUTIONS = "executions";
    
    protected CachedEntityMatcher<Entity> executionsByParentIdMatcher = (CachedEntityMatcher) new ExecutionsByParentExecutionIdEntityMatcher();

    protected CachedEntityMatcher<Entity> executionsByProcessInstanceIdMatcher = (CachedEntityMatcher) new ExecutionsByProcessInstanceIdEntityMatcher();
    
    protected CachedEntityMatcher<Entity> executionsWithSameRootProcessInstanceIdMatcher = (CachedEntityMatcher) new ExecutionsWithSameRootProcessInstanceIdMatcher();
    
    protected CachedEntityMatcher<Entity> inactiveExecutionsInActivityAndProcInstMatcher = (CachedEntityMatcher) new InactiveExecutionsInActivityAndProcInstMatcher();

    protected CachedEntityMatcher<Entity> executionsByRootProcessInstanceMatcher = (CachedEntityMatcher) new ExecutionsByRootProcessInstanceMatcher();

    protected CachedEntityMatcher<Entity> executionByProcessInstanceMatcher = (CachedEntityMatcher) new ExecutionByProcessInstanceMatcher();

    protected CachedEntityMatcher<Entity> inactiveExecutionsByProcInstMatcher = (CachedEntityMatcher) new InactiveExecutionsByProcInstMatcher();

    public ExecutionEntity create() {
       return new ExecutionEntityImpl();
    }
    
    @Override
    public String getCollection() {
        return COLLECTION_EXECUTIONS;
    }

    @Override
    public BasicDBObject createUpdateObject(Entity entity) {
        ExecutionEntity executionEntity = (ExecutionEntity) entity;
        BasicDBObject updateObject = null;
        updateObject = setUpdateProperty(executionEntity, "isActive", executionEntity.isActive(), updateObject);
        updateObject = setUpdateProperty(executionEntity, "isScope", executionEntity.isScope(), updateObject);
        updateObject = setUpdateProperty(executionEntity, "isConcurrent", executionEntity.isConcurrent(), updateObject);
        updateObject = setUpdateProperty(executionEntity, "isEventScope", executionEntity.isEventScope(), updateObject);
        updateObject = setUpdateProperty(executionEntity, "activityId", executionEntity.getActivityId(), updateObject);
        updateObject = setUpdateProperty(executionEntity, "parentId", executionEntity.getParentId(), updateObject);
        updateObject = setUpdateProperty(executionEntity, "superExecutionId", executionEntity.getSuperExecutionId(), updateObject);
        return updateObject;
    }
   
    public ExecutionEntity findSubProcessInstanceBySuperExecutionId(String superExecutionId) {
       List<ExecutionEntity> executionEntities = getMongoDbSession().find(COLLECTION_EXECUTIONS, Filters.eq("superExecutionId", superExecutionId));
       if (executionEntities.size() > 1) {
           throw new FlowableException("Programmatics error: multiple super executions found");
       } 
       if (!executionEntities.isEmpty()) {
           return executionEntities.get(0);
       }
       return null;
    }

    public List<ExecutionEntity> findChildExecutionsByParentExecutionId(String parentExecutionId) {
        Bson filter = Filters.eq("parentId", parentExecutionId);
        return getMongoDbSession().find(COLLECTION_EXECUTIONS, filter, parentExecutionId, ExecutionEntityImpl.class, executionsByParentIdMatcher, true);
    }

    public List<ExecutionEntity> findChildExecutionsByProcessInstanceId(String processInstanceId) {
        if (isExecutionTreeFetched(processInstanceId)) {
            return getMongoDbSession().findFromCache(executionByProcessInstanceMatcher, processInstanceId, ExecutionEntityImpl.class);
        } else {
            Bson filter = Filters.and(
                Filters.eq("processInstanceId", processInstanceId),
                Filters.exists("parentId")
            );
            return getMongoDbSession().find(COLLECTION_EXECUTIONS, filter,
                processInstanceId, ExecutionEntityImpl.class, executionsByProcessInstanceIdMatcher, true);
        }
    }

    public List<ExecutionEntity> findExecutionsByParentExecutionAndActivityIds(String parentExecutionId,
            Collection<String> activityIds) {
       throw new UnsupportedOperationException();
    }

    public long findExecutionCountByQueryCriteria(ExecutionQueryImpl executionQuery) {
        return getMongoDbSession().count(COLLECTION_EXECUTIONS, createFilter(executionQuery));
    }

    public List<ExecutionEntity> findExecutionsByQueryCriteria(ExecutionQueryImpl executionQuery) {
        return getMongoDbSession().find(COLLECTION_EXECUTIONS, createFilter(executionQuery));
    }

    protected Bson createFilter(ExecutionQueryImpl executionQuery) {
        List<Bson> filters = new ArrayList<>();
        if (executionQuery.getExecutionId() != null) {
            filters.add(Filters.eq("_id", executionQuery.getExecutionId()));
        }
        if (executionQuery.getProcessDefinitionId() != null) {
            filters.add(Filters.eq("processDefinitionId", executionQuery.getProcessDefinitionId()));
        }
        if (executionQuery.getProcessDefinitionIds() != null) {
            filters.add(Filters.in("processDefinitionId", executionQuery.getProcessDefinitionId()));
        }
        if (executionQuery.getDeploymentId() != null) {
            // TODO (done with join in relation counterpart)
        }
        if (executionQuery.getDeploymentIds() != null) {
            // TODO (done with join in relation counterpart)
        }
        if (executionQuery.getProcessDefinitionKey() != null) {
            // TODO (done with join in relation counterpart)
        }
        if (executionQuery.getProcessDefinitionKeys() != null) {
            // TODO
        }
        if (executionQuery.getProcessDefinitionCategory() != null) {
            // TODO (done with join in relation counterpart)
        }
        if (executionQuery.getProcessDefinitionName() != null) {
            // TODO (done with join in relation counterpart)
        }
        if (executionQuery.getProcessDefinitionVersion() != null) {
            // TODO (done with join in relation counterpart)
        }
        if (executionQuery.getProcessDefinitionEngineVersion() != null) {
            // TODO (done with join in relation counterpart)
        }
        if (executionQuery.getActivityId() != null) {
            filters.add(Filters.eq("activityId", executionQuery.getActivityId()));
        }
        if (executionQuery.getProcessInstanceId() != null) {
            filters.add(Filters.eq("processInstanceId", executionQuery.getProcessInstanceId()));
        }
        if (executionQuery.getParentId() != null) {
            filters.add(Filters.eq("parentId", executionQuery.getParentId()));
        }
        if (executionQuery.isOnlyChildExecutions()) {
            filters.add(Filters.exists("parentId"));
        }
        if (executionQuery.isOnlySubProcessExecutions()) {
            // TODO
        }
        if (executionQuery.isOnlyProcessInstanceExecutions()) {
            filters.add(Filters.not(Filters.exists("parentId")));
        }
        if (executionQuery.getRootProcessInstanceId() != null) {
            filters.add(Filters.eq("rootProcessInstanceId", executionQuery.getRootProcessInstanceId()));
        }
        if (executionQuery.getStartedBefore() != null) {
            filters.add(Filters.lt("startTime", executionQuery.getStartedBefore()));
        }
        if (executionQuery.getStartedAfter() != null) {
            filters.add(Filters.gt("startTime", executionQuery.getStartedAfter()));
        }
        if (executionQuery.getStartedBy() != null) {
            filters.add(Filters.eq("startedBy", executionQuery.getStartedBy()));
        }
        if (executionQuery.getSuperProcessInstanceId() != null) {
            // TODO
        }
        if (executionQuery.getSubProcessInstanceId() != null) {
            // TODO
        }
        if (executionQuery.isExcludeSubprocesses()) {
            // TODO
        }
        if (executionQuery.getSuspensionState() != null) {
            // TODO
        }
        if (executionQuery.getBusinessKey() != null) {
            // TODO
        }
        if (executionQuery.isActive()) {
            filters.add(Filters.eq("isActive", true));
        }
        if (executionQuery.getInvolvedGroups() != null) {
            // TODO
        }
        if (executionQuery.getInvolvedUser() != null) {
            // TODO
        }
        if (executionQuery.getName() != null) {
            filters.add(Filters.eq("name", executionQuery.getName()));
        }
        if (executionQuery.getNameLike() != null) {
            filters.add(Filters.regex("name", executionQuery.getNameLike().replace("%", ".*")));
        }
        if (executionQuery.getNameLikeIgnoreCase() != null) {
            // TODO
        }
        if (executionQuery.getTenantId() != null) {
            filters.add(Filters.eq("tenantId", executionQuery.getTenantId()));
        }
        if (executionQuery.getTenantIdLike() != null) {
            filters.add(Filters.regex("tenantId", executionQuery.getTenantIdLike().replace("%", ".*")));
        }
        if (executionQuery.isWithoutTenantId()) {
            filters.add(Filters.or(Filters.eq("tenantId", ProcessEngineConfiguration.NO_TENANT_ID), Filters.not(Filters.exists("tenantId"))));
        }

        return makeAndFilter(filters);
    }

    public long findProcessInstanceCountByQueryCriteria(ProcessInstanceQueryImpl executionQuery) {
        return getMongoDbSession().count(COLLECTION_EXECUTIONS, Filters.eq("parentId", null));
    }

    public List<ProcessInstance> findProcessInstanceByQueryCriteria(ProcessInstanceQueryImpl processInstanceQuery) {
        return getMongoDbSession().find(COLLECTION_EXECUTIONS, createFilter(processInstanceQuery));
    }

    public List<ExecutionEntity> findExecutionsByRootProcessInstanceId(String rootProcessInstanceId) {
        if (isExecutionTreeFetched(rootProcessInstanceId)) {
            return getMongoDbSession().findFromCache(executionsByRootProcessInstanceMatcher, rootProcessInstanceId, ExecutionEntityImpl.class);
        } else {
            return getMongoDbSession().find(COLLECTION_EXECUTIONS, Filters.eq("rootProcessInstanceId", rootProcessInstanceId),
                rootProcessInstanceId, ExecutionEntityImpl.class, inactiveExecutionsInActivityAndProcInstMatcher, true);
        }
    }

    public List<ExecutionEntity> findExecutionsByProcessInstanceId(String processInstanceId) {
        if (isExecutionTreeFetched(processInstanceId)) {
            return getMongoDbSession().findFromCache(executionByProcessInstanceMatcher, processInstanceId, ExecutionEntityImpl.class);
        } else {
            return getMongoDbSession().find(COLLECTION_EXECUTIONS, Filters.eq("processInstanceId", processInstanceId), processInstanceId,
                ExecutionEntityImpl.class, executionByProcessInstanceMatcher, true);
        }
    }

    public List<ProcessInstance> findProcessInstanceAndVariablesByQueryCriteria(ProcessInstanceQueryImpl executionQuery) {
       throw new UnsupportedOperationException();
    }

    public Collection<ExecutionEntity> findInactiveExecutionsByProcessInstanceId(String processInstanceId) {
        HashMap<String, Object> params = new HashMap<>(2);
        params.put("processInstanceId", processInstanceId);
        params.put("isActive", false);

        if (isExecutionTreeFetched(processInstanceId)) {
            return getMongoDbSession().findFromCache(inactiveExecutionsByProcInstMatcher, processInstanceId, ExecutionEntityImpl.class);
        } else {
            Bson filter = Filters.and(
                Filters.eq("processInstanceId", processInstanceId),
                Filters.eq("isActive", false)
            );
            return getMongoDbSession().find(COLLECTION_EXECUTIONS, filter, params, ExecutionEntityImpl.class, inactiveExecutionsByProcInstMatcher, true);
        }
    }

    public Collection<ExecutionEntity> findInactiveExecutionsByActivityIdAndProcessInstanceId(String activityId, String processInstanceId) {
        HashMap<String, Object> params = new HashMap<>(3);
        params.put("activityId", activityId);
        params.put("processInstanceId", processInstanceId);
        params.put("isActive", false);
        
        if (isExecutionTreeFetched(processInstanceId)) {
            return getMongoDbSession().findFromCache(inactiveExecutionsInActivityAndProcInstMatcher, params, ExecutionEntityImpl.class);
        } else {
            Bson filter = Filters.and(
                Filters.eq("activityId", activityId),
                Filters.eq("processInstanceId", processInstanceId),
                Filters.eq("isActive", false)
            );
            return getMongoDbSession().find(COLLECTION_EXECUTIONS, filter, params, ExecutionEntityImpl.class, inactiveExecutionsInActivityAndProcInstMatcher, true);
        }
    }

    public List<String> findProcessInstanceIdsByProcessDefinitionId(String processDefinitionId) {
        // Note: no caching, similar to Mybatis version
        Bson filter = Filters.and(
            Filters.eq("processDefinitionId", processDefinitionId),
            Filters.not(Filters.exists("parentId"))
        );
        return getMongoDbSession().find(COLLECTION_EXECUTIONS, filter);
    }

    public List<Execution> findExecutionsByNativeQuery(Map<String, Object> parameterMap) {
       throw new UnsupportedOperationException();
    }

    public List<ProcessInstance> findProcessInstanceByNativeQuery(Map<String, Object> parameterMap) {
       throw new UnsupportedOperationException();
    }

    public long findExecutionCountByNativeQuery(Map<String, Object> parameterMap) {
        return 0;
    }

    public void updateExecutionTenantIdForDeployment(String deploymentId, String newTenantId) {
        throw new UnsupportedOperationException();
    }

    public void updateProcessInstanceLockTime(String processInstanceId, Date lockDate, Date expirationTime) {
        BasicDBObject updateObject = new BasicDBObject();
        updateObject.append("lockTime", lockDate);
        
        MongoCollection<Document> mongoDbCollection = getMongoDbSession().getCollection(COLLECTION_EXECUTIONS);
        Bson filter = Filters.and(Filters.eq("_id", processInstanceId), Filters.or(Filters.eq("lockTime", null), Filters.lt("lockTime", expirationTime)));
        UpdateResult updateResult = mongoDbCollection.updateOne(filter, new Document().append("$set", updateObject));
        if (updateResult.getModifiedCount() != 1) {
            throw new FlowableOptimisticLockingException("Could not lock process instance");
        }
    }

    public void updateAllExecutionRelatedEntityCountFlags(boolean newValue) {
        throw new UnsupportedOperationException();        
    }

    public void clearProcessInstanceLockTime(String processInstanceId) {
        BasicDBObject updateObject = new BasicDBObject();
        updateObject.append("lockTime", null);
        
        MongoCollection<Document> mongoDbCollection = getMongoDbSession().getCollection(COLLECTION_EXECUTIONS);
        mongoDbCollection.updateOne(Filters.eq("_id", processInstanceId), new Document().append("$set", updateObject));
    }
    
    protected boolean isExecutionTreeFetched(final String executionId) {
        
        // Need to get the cache result before doing the findById
        ExecutionEntity cachedExecutionEntity = getMongoDbSession().getEntityCache().findInCache(ExecutionEntityImpl.class, executionId);
        
        // Find execution in db or cache to check process definition setting for execution fetch.
        // If not set, no extra work is done. The execution is in the cache however now as a side-effect of calling this method.
        ExecutionEntity executionEntity = (cachedExecutionEntity != null) ? cachedExecutionEntity : getMongoDbSession().findOne(COLLECTION_EXECUTIONS, executionId);
        if (!ProcessDefinitionUtil.getProcess(executionEntity.getProcessDefinitionId()).isEnableEagerExecutionTreeFetching()) {
            return false;
        }
        
        // If it's in the cache, the execution and its tree have been fetched before. No need to do anything more.
        if (cachedExecutionEntity != null) {
            return true;
        }
        
        // Fetches execution tree. This will store them in the cache and thus avoid extra database calls.
        getMongoDbSession().find(COLLECTION_EXECUTIONS, Filters.eq("rootProcessInstanceId", executionEntity.getRootProcessInstanceId()), 
                executionId, ExecutionEntity.class, executionsWithSameRootProcessInstanceIdMatcher, true);
        
        return true;
    }
    
    protected Bson createFilter(ProcessInstanceQueryImpl processInstanceQuery) {
        List<Bson> andFilters = new ArrayList<>();
        
        andFilters.add(Filters.eq("parentId", null));
        
        if (processInstanceQuery.getExecutionId() != null) {
            andFilters.add(Filters.eq("executionId", processInstanceQuery.getExecutionId()));
        }
        
        if (processInstanceQuery.getProcessInstanceId() != null) {
            andFilters.add(Filters.eq("processInstanceId", processInstanceQuery.getProcessInstanceId()));
        }
        
        if (processInstanceQuery.getDeploymentId() != null) {
            andFilters.add(Filters.eq("deploymentId", processInstanceQuery.getDeploymentId()));
        }
        
        if (processInstanceQuery.getProcessDefinitionId() != null) {
            andFilters.add(Filters.eq("processDefinitionId", processInstanceQuery.getProcessDefinitionId()));
        }
        
        if (processInstanceQuery.getRootProcessInstanceId() != null) {
            andFilters.add(Filters.eq("rootProcessInstanceId", processInstanceQuery.getRootProcessInstanceId()));
        }
        
        if (processInstanceQuery.getSuperProcessInstanceId() != null) {
            List<ExecutionEntity> superExecutions = getMongoDbSession().find(COLLECTION_EXECUTIONS, 
                    Filters.eq("processInstanceId", processInstanceQuery.getSuperProcessInstanceId()));
            
            List<String> superExecutionIds = new ArrayList<>();
            for (ExecutionEntity execution : superExecutions) {
                superExecutionIds.add(execution.getId());
            }
            
            andFilters.add(Filters.in("superExecutionId", superExecutionIds));
        }
        
        if (processInstanceQuery.getSubProcessInstanceId() != null) {
            andFilters.add(Filters.eq("subProcessInstanceId", processInstanceQuery.getSubProcessInstanceId()));
        }
        
        Bson filter = Filters.and(andFilters.toArray(new Bson[andFilters.size()]));
        
        return filter;
    }
}
