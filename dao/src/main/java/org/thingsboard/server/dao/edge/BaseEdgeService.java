/**
 * Copyright © 2016-2019 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.EntitySubtype;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.Event;
import org.thingsboard.server.common.data.ShortEdgeInfo;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.edge.Edge;
import org.thingsboard.server.common.data.edge.EdgeQueueEntry;
import org.thingsboard.server.common.data.edge.EdgeSearchQuery;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.TextPageData;
import org.thingsboard.server.common.data.page.TextPageLink;
import org.thingsboard.server.common.data.page.TimePageData;
import org.thingsboard.server.common.data.page.TimePageLink;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.EntitySearchDirection;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.dao.customer.CustomerDao;
import org.thingsboard.server.dao.dashboard.DashboardService;
import org.thingsboard.server.dao.entity.AbstractEntityService;
import org.thingsboard.server.dao.event.EventService;
import org.thingsboard.server.dao.exception.DataValidationException;
import org.thingsboard.server.dao.rule.RuleChainService;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.service.PaginatedRemover;
import org.thingsboard.server.dao.service.Validator;
import org.thingsboard.server.dao.tenant.TenantDao;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.thingsboard.server.common.data.CacheConstants.EDGE_CACHE;
import static org.thingsboard.server.dao.DaoUtil.toUUIDs;
import static org.thingsboard.server.dao.model.ModelConstants.NULL_UUID;
import static org.thingsboard.server.dao.service.Validator.validateId;
import static org.thingsboard.server.dao.service.Validator.validateIds;
import static org.thingsboard.server.dao.service.Validator.validatePageLink;
import static org.thingsboard.server.dao.service.Validator.validateString;

@Service
@Slf4j
public class BaseEdgeService extends AbstractEntityService implements EdgeService {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static final String INCORRECT_TENANT_ID = "Incorrect tenantId ";
    public static final String INCORRECT_PAGE_LINK = "Incorrect page link ";
    public static final String INCORRECT_CUSTOMER_ID = "Incorrect customerId ";
    public static final String INCORRECT_EDGE_ID = "Incorrect edgeId ";

    @Autowired
    private EdgeDao edgeDao;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private CustomerDao customerDao;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private EventService eventService;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private RuleChainService ruleChainService;

    @Override
    public Edge findEdgeById(TenantId tenantId, EdgeId edgeId) {
        log.trace("Executing findEdgeById [{}]", edgeId);
        validateId(edgeId, INCORRECT_EDGE_ID + edgeId);
        return edgeDao.findById(tenantId, edgeId.getId());
    }

    @Override
    public ListenableFuture<Edge> findEdgeByIdAsync(TenantId tenantId, EdgeId edgeId) {
        log.trace("Executing findEdgeById [{}]", edgeId);
        validateId(edgeId, INCORRECT_EDGE_ID + edgeId);
        return edgeDao.findByIdAsync(tenantId, edgeId.getId());
    }

    @Cacheable(cacheNames = EDGE_CACHE, key = "{#tenantId, #name}")
    @Override
    public Edge findEdgeByTenantIdAndName(TenantId tenantId, String name) {
        log.trace("Executing findEdgeByTenantIdAndName [{}][{}]", tenantId, name);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        Optional<Edge> edgeOpt = edgeDao.findEdgeByTenantIdAndName(tenantId.getId(), name);
        return edgeOpt.orElse(null);
    }

    @Override
    public Optional<Edge> findEdgeByRoutingKey(TenantId tenantId, String routingKey) {
        log.trace("Executing findEdgeByRoutingKey [{}]", routingKey);
        Validator.validateString(routingKey, "Incorrect edge routingKey for search request.");
        return edgeDao.findByRoutingKey(tenantId.getId(), routingKey);
    }

    @CacheEvict(cacheNames = EDGE_CACHE, key = "{#edge.tenantId, #edge.name}")
    @Override
    public Edge saveEdge(Edge edge) {
        log.trace("Executing saveEdge [{}]", edge);
        edgeValidator.validate(edge, Edge::getTenantId);
        Edge savedEdge = edgeDao.save(edge.getTenantId(), edge);
        dashboardService.updateEdgeDashboards(savedEdge.getTenantId(), savedEdge.getId());
        return savedEdge;
    }

    @Override
    public Edge assignEdgeToCustomer(TenantId tenantId, EdgeId edgeId, CustomerId customerId) {
        Edge edge = findEdgeById(tenantId, edgeId);
        edge.setCustomerId(customerId);
        return saveEdge(edge);
    }

    @Override
    public Edge unassignEdgeFromCustomer(TenantId tenantId, EdgeId edgeId) {
        Edge edge = findEdgeById(tenantId, edgeId);
        edge.setCustomerId(null);
        return saveEdge(edge);
    }

    @Override
    public void deleteEdge(TenantId tenantId, EdgeId edgeId) {
        log.trace("Executing deleteEdge [{}]", edgeId);
        validateId(edgeId, INCORRECT_EDGE_ID + edgeId);

        Edge edge = edgeDao.findById(tenantId, edgeId.getId());

        dashboardService.unassignEdgeDashboards(tenantId, edgeId);
        ruleChainService.unassignEdgeRuleChains(tenantId, edgeId);

        List<Object> list = new ArrayList<>();
        list.add(edge.getTenantId());
        list.add(edge.getName());
        Cache cache = cacheManager.getCache(EDGE_CACHE);
        cache.evict(list);

        deleteEntityRelations(tenantId, edgeId);

        edgeDao.removeById(tenantId, edgeId.getId());
    }

    @Override
    public TextPageData<Edge> findEdgesByTenantId(TenantId tenantId, TextPageLink pageLink) {
        log.trace("Executing findEdgesByTenantId, tenantId [{}], pageLink [{}]", tenantId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validatePageLink(pageLink, INCORRECT_PAGE_LINK + pageLink);
        List<Edge> edges = edgeDao.findEdgesByTenantId(tenantId.getId(), pageLink);
        return new TextPageData<>(edges, pageLink);
    }

    @Override
    public TextPageData<Edge> findEdgesByTenantIdAndType(TenantId tenantId, String type, TextPageLink pageLink) {
        log.trace("Executing findEdgesByTenantIdAndType, tenantId [{}], type [{}], pageLink [{}]", tenantId, type, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateString(type, "Incorrect type " + type);
        validatePageLink(pageLink, INCORRECT_PAGE_LINK + pageLink);
        List<Edge> edges = edgeDao.findEdgesByTenantIdAndType(tenantId.getId(), type, pageLink);
        return new TextPageData<>(edges, pageLink);
    }

    @Override
    public ListenableFuture<List<Edge>> findEdgesByTenantIdAndIdsAsync(TenantId tenantId, List<EdgeId> edgeIds) {
        log.trace("Executing findEdgesByTenantIdAndIdsAsync, tenantId [{}], edgeIds [{}]", tenantId, edgeIds);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateIds(edgeIds, "Incorrect edgeIds " + edgeIds);
        return edgeDao.findEdgesByTenantIdAndIdsAsync(tenantId.getId(), toUUIDs(edgeIds));
    }


    @Override
    public void deleteEdgesByTenantId(TenantId tenantId) {
        log.trace("Executing deleteEdgesByTenantId, tenantId [{}]", tenantId);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        tenantEdgesRemover.removeEntities(tenantId, tenantId);
    }

    @Override
    public TextPageData<Edge> findEdgesByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId, TextPageLink pageLink) {
        log.trace("Executing findEdgesByTenantIdAndCustomerId, tenantId [{}], customerId [{}], pageLink [{}]", tenantId, customerId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        validatePageLink(pageLink, INCORRECT_PAGE_LINK + pageLink);
        List<Edge> edges = edgeDao.findEdgesByTenantIdAndCustomerId(tenantId.getId(), customerId.getId(), pageLink);
        return new TextPageData<>(edges, pageLink);
    }

    @Override
    public TextPageData<Edge> findEdgesByTenantIdAndCustomerIdAndType(TenantId tenantId, CustomerId customerId, String type, TextPageLink pageLink) {
        log.trace("Executing findEdgesByTenantIdAndCustomerIdAndType, tenantId [{}], customerId [{}], type [{}], pageLink [{}]", tenantId, customerId, type, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        validateString(type, "Incorrect type " + type);
        validatePageLink(pageLink, INCORRECT_PAGE_LINK + pageLink);
        List<Edge> edges = edgeDao.findEdgesByTenantIdAndCustomerIdAndType(tenantId.getId(), customerId.getId(), type, pageLink);
        return new TextPageData<>(edges, pageLink);
    }

    @Override
    public ListenableFuture<List<Edge>> findEdgesByTenantIdCustomerIdAndIdsAsync(TenantId tenantId, CustomerId customerId, List<EdgeId> edgeIds) {
        log.trace("Executing findEdgesByTenantIdCustomerIdAndIdsAsync, tenantId [{}], customerId [{}], edgeIds [{}]", tenantId, customerId, edgeIds);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        validateIds(edgeIds, "Incorrect edgeIds " + edgeIds);
        return edgeDao.findEdgesByTenantIdCustomerIdAndIdsAsync(tenantId.getId(),
                customerId.getId(), toUUIDs(edgeIds));
    }

    @Override
    public void unassignCustomerEdges(TenantId tenantId, CustomerId customerId) {
        log.trace("Executing unassignCustomerEdges, tenantId [{}], customerId [{}]", tenantId, customerId);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        customerEdgeUnasigner.removeEntities(tenantId, customerId);
    }

    @Override
    public ListenableFuture<List<Edge>> findEdgesByQuery(TenantId tenantId, EdgeSearchQuery query) {
        ListenableFuture<List<EntityRelation>> relations = relationService.findByQuery(tenantId, query.toEntitySearchQuery());
        ListenableFuture<List<Edge>> edges = Futures.transformAsync(relations, r -> {
            EntitySearchDirection direction = query.toEntitySearchQuery().getParameters().getDirection();
            List<ListenableFuture<Edge>> futures = new ArrayList<>();
            for (EntityRelation relation : r) {
                EntityId entityId = direction == EntitySearchDirection.FROM ? relation.getTo() : relation.getFrom();
                if (entityId.getEntityType() == EntityType.EDGE) {
                    futures.add(findEdgeByIdAsync(tenantId, new EdgeId(entityId.getId())));
                }
            }
            return Futures.successfulAsList(futures);
        });

        edges = Futures.transform(edges, new Function<List<Edge>, List<Edge>>() {
            @Nullable
            @Override
            public List<Edge> apply(@Nullable List<Edge> edgeList) {
                return edgeList == null ? Collections.emptyList() : edgeList.stream().filter(edge -> query.getEdgeTypes().contains(edge.getType())).collect(Collectors.toList());
            }
        });

        return edges;
    }

    @Override
    public ListenableFuture<List<EntitySubtype>> findEdgeTypesByTenantId(TenantId tenantId) {
        log.trace("Executing findEdgeTypesByTenantId, tenantId [{}]", tenantId);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        ListenableFuture<List<EntitySubtype>> tenantEdgeTypes = edgeDao.findTenantEdgeTypesAsync(tenantId.getId());
        return Futures.transform(tenantEdgeTypes,
                edgeTypes -> {
                    edgeTypes.sort(Comparator.comparing(EntitySubtype::getType));
                    return edgeTypes;
                });
    }

    @Override
    public void pushEventToEdge(TenantId tenantId, TbMsg tbMsg) {
        try {
            switch (tbMsg.getOriginator().getEntityType()) {
                case ASSET:
                    processAsset(tenantId, tbMsg);
                    break;
                case DEVICE:
                    processDevice(tenantId, tbMsg);
                    break;
                case DASHBOARD:
                    processDashboard(tenantId, tbMsg);
                    break;
                case RULE_CHAIN:
                    processRuleChain(tenantId, tbMsg);
                    break;
                case ENTITY_VIEW:
                    processEntityView(tenantId, tbMsg);
                    break;
                default:
                    log.debug("Entity type [{}] is not designed to be pushed to edge", tbMsg.getOriginator().getEntityType());
            }
        } catch (IOException e) {
            log.error("Can't push to edge updates, entity type [{}], data [{}]", tbMsg.getOriginator().getEntityType(), tbMsg.getData(), e);
        }


    }

    private void processDevice(TenantId tenantId, TbMsg tbMsg) {
        // TODO
    }

    private void processDashboard(TenantId tenantId, TbMsg tbMsg) {
        processAssignedEntity(tenantId, tbMsg, EntityType.DASHBOARD);
    }

    private void processEntityView(TenantId tenantId, TbMsg tbMsg) {
        // TODO
    }

    private void processAsset(TenantId tenantId, TbMsg tbMsg) {
        // TODO
    }

    private void processAssignedEntity(TenantId tenantId, TbMsg tbMsg, EntityType entityType) {
        EdgeId edgeId;
        switch (tbMsg.getType()) {
            case DataConstants.ENTITY_ASSIGNED_TO_EDGE:
                edgeId = new EdgeId(UUID.fromString(tbMsg.getMetaData().getValue("assignedEdgeId")));
                pushEventToEdge(tenantId, edgeId, tbMsg.getType(), entityType, tbMsg.getData());
                break;
            case DataConstants.ENTITY_UNASSIGNED_FROM_EDGE:
                edgeId = new EdgeId(UUID.fromString(tbMsg.getMetaData().getValue("unassignedEdgeId")));
                pushEventToEdge(tenantId, edgeId, tbMsg.getType(), entityType, tbMsg.getData());
                break;
        }
    }

    private void processRuleChain(TenantId tenantId, TbMsg tbMsg) throws IOException {
        switch (tbMsg.getType()) {
            case DataConstants.ENTITY_ASSIGNED_TO_EDGE:
            case DataConstants.ENTITY_UNASSIGNED_FROM_EDGE:
                processAssignedEntity(tenantId, tbMsg, EntityType.RULE_CHAIN);
                break;
            case DataConstants.ENTITY_DELETED:
            case DataConstants.ENTITY_CREATED:
            case DataConstants.ENTITY_UPDATED:
                RuleChain ruleChain = mapper.readValue(tbMsg.getData(), RuleChain.class);
                for (ShortEdgeInfo assignedEdge : ruleChain.getAssignedEdges()) {
                    pushEventToEdge(tenantId, assignedEdge.getEdgeId(), tbMsg.getType(), EntityType.RULE_CHAIN, tbMsg.getData());
                }
                break;
            default:
                log.warn("Unsupported message type " + tbMsg.getType());
        }

    }

    private void pushEventToEdge(TenantId tenantId, EdgeId edgeId, String type, EntityType entityType, String data) {
        log.debug("Pushing event to edge queue. tenantId [{}], edgeId [{}], type [{}], data [{}]", tenantId, edgeId, type, data);

        EdgeQueueEntry queueEntry = new EdgeQueueEntry();
        queueEntry.setType(type);
        queueEntry.setEntityType(entityType);
        queueEntry.setData(data);

        Event event = new Event();
        event.setEntityId(edgeId);
        event.setTenantId(tenantId);
        event.setType(DataConstants.EDGE_QUEUE_EVENT_TYPE);
        event.setBody(mapper.valueToTree(queueEntry));
        eventService.saveAsync(event);
    }

    @Override
    public TimePageData<Event> findQueueEvents(TenantId tenantId, EdgeId edgeId, TimePageLink pageLink) {
        return eventService.findEvents(tenantId, edgeId, DataConstants.EDGE_QUEUE_EVENT_TYPE, pageLink);
    }

    private DataValidator<Edge> edgeValidator =
            new DataValidator<Edge>() {

                @Override
                protected void validateCreate(TenantId tenantId, Edge edge) {
                    edgeDao.findEdgeByTenantIdAndName(edge.getTenantId().getId(), edge.getName()).ifPresent(
                            d -> {
                                throw new DataValidationException("Edge with such name already exists!");
                            }
                    );
                }

                @Override
                protected void validateUpdate(TenantId tenantId, Edge edge) {
                    edgeDao.findEdgeByTenantIdAndName(edge.getTenantId().getId(), edge.getName()).ifPresent(
                            e -> {
                                if (!e.getUuidId().equals(edge.getUuidId())) {
                                    throw new DataValidationException("Edge with such name already exists!");
                                }
                            }
                    );
                }

                @Override
                protected void validateDataImpl(TenantId tenantId, Edge edge) {
                    if (StringUtils.isEmpty(edge.getType())) {
                        throw new DataValidationException("Edge type should be specified!");
                    }
                    if (StringUtils.isEmpty(edge.getName())) {
                        throw new DataValidationException("Edge name should be specified!");
                    }
                    if (edge.getTenantId() == null) {
                        throw new DataValidationException("Edge should be assigned to tenant!");
                    } else {
                        Tenant tenant = tenantDao.findById(edge.getTenantId(), edge.getTenantId().getId());
                        if (tenant == null) {
                            throw new DataValidationException("Edge is referencing to non-existent tenant!");
                        }
                    }
                    if (edge.getCustomerId() == null) {
                        edge.setCustomerId(new CustomerId(NULL_UUID));
                    } else if (!edge.getCustomerId().getId().equals(NULL_UUID)) {
                        Customer customer = customerDao.findById(edge.getTenantId(), edge.getCustomerId().getId());
                        if (customer == null) {
                            throw new DataValidationException("Can't assign edge to non-existent customer!");
                        }
                        if (!customer.getTenantId().getId().equals(edge.getTenantId().getId())) {
                            throw new DataValidationException("Can't assign edge to customer from different tenant!");
                        }
                    }
                }
            };

    private PaginatedRemover<TenantId, Edge> tenantEdgesRemover =
            new PaginatedRemover<TenantId, Edge>() {

                @Override
                protected List<Edge> findEntities(TenantId tenantId, TenantId id, TextPageLink pageLink) {
                    return edgeDao.findEdgesByTenantId(id.getId(), pageLink);
                }

                @Override
                protected void removeEntity(TenantId tenantId, Edge entity) {
                    deleteEdge(tenantId, new EdgeId(entity.getUuidId()));
                }
            };

    private PaginatedRemover<CustomerId, Edge> customerEdgeUnasigner = new PaginatedRemover<CustomerId, Edge>() {

        @Override
        protected List<Edge> findEntities(TenantId tenantId, CustomerId id, TextPageLink pageLink) {
            return edgeDao.findEdgesByTenantIdAndCustomerId(tenantId.getId(), id.getId(), pageLink);
        }

        @Override
        protected void removeEntity(TenantId tenantId, Edge entity) {
            unassignEdgeFromCustomer(tenantId, new EdgeId(entity.getUuidId()));
        }
    };

}
