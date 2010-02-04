/*
 * JPAContainer
 * Copyright (C) 2009 Oy IT Mill Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.vaadin.addons.jpacontainer.provider;

import com.vaadin.addons.jpacontainer.EntityProvider;
import com.vaadin.addons.jpacontainer.Filter;
import com.vaadin.addons.jpacontainer.Filter.PropertyIdPreprocessor;
import com.vaadin.addons.jpacontainer.MutableEntityProvider;
import com.vaadin.addons.jpacontainer.SortBy;
import com.vaadin.addons.jpacontainer.filter.CompositeFilter;
import com.vaadin.addons.jpacontainer.filter.Filters;
import com.vaadin.addons.jpacontainer.filter.IntervalFilter;
import com.vaadin.addons.jpacontainer.filter.Junction;
import com.vaadin.addons.jpacontainer.filter.ValueFilter;
import com.vaadin.addons.jpacontainer.metadata.EntityClassMetadata;
import com.vaadin.addons.jpacontainer.metadata.MetadataFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;
import javax.persistence.EntityTransaction;
import javax.persistence.Query;

/**
 * A mutable entity provider that works with a local {@link EntityManager}. Most important
 * features and limitations:
 * <ul>
 *   <li>Does not do any internal caching, all information is always accessed directly from the EntityManager</li>
 *   <li>Uses lazy-loading of entities (references and collections within the entities should be configured to be fetched eagerly, though)</li>
 *   <li>Performs a serialize-deserialize cycle to clone entities in order to detach them from the persistence context (<b>This is ugly!</b<)</li>
 *   <li>Once the entity manager has been set, it cannot be changed</li>
 *   <li>Supports both internal and external transaction handling</li>
 *   <li><strong>Does NOT currently support embedded identifiers!</strong></li>
 * </ul>
 *
 * @author Petter Holmström (IT Mill)
 * @since 1.0
 */
public class LocalEntityProvider<T> implements EntityProvider<T>,
        MutableEntityProvider<T>, Serializable {

    private EntityManager entityManager;
    private EntityClassMetadata<T> entityClassMetadata;

    /**
     * Creates a new <code>LocalEntityProvider</code>.
     *
     * @param entityClass the entity class (must not be null).
     * @param entityManager the entity manager to use (must not be null).
     */
    public LocalEntityProvider(Class<T> entityClass, EntityManager entityManager) {
        assert entityClass != null : "entityClass must not be null";
        assert entityManager != null : "entityManager must not be null";
        this.entityManager = entityManager;
        this.entityClassMetadata = MetadataFactory.getInstance().
                getEntityClassMetadata(entityClass);

        if (entityClassMetadata.hasEmbeddedIdentifier()) {
            // TODO Add support for embedded identifiers
            throw new IllegalArgumentException(
                    "Embedded identifiers are currently not supported!");
        }
    }

    /**
     * Gets the metadata for the entity class.
     *
     * @return the metadata (never null).
     */
    protected EntityClassMetadata<T> getEntityClassMetadata() {
        return this.entityClassMetadata;
    }

    /**
     * Gets the entity manager.
     *
     * @return the entity manager (never null).
     */
    protected EntityManager getEntityManager() {
        return this.entityManager;
    }

    /**
     * Creates a copy of <code>original</code> and adds an entry for the primary key
     * to the end of the list.
     *
     * @param original the original list of sorting instructions (must not be null, but may be empty).
     * @return a new list with the added entry for the primary key.
     */
    protected List<SortBy> addPrimaryKeyToSortList(List<SortBy> original) {
        ArrayList<SortBy> newList = new ArrayList<SortBy>();
        newList.addAll(original);
        newList.add(new SortBy(getEntityClassMetadata().getIdentifierProperty().
                getName(), true));
        return Collections.unmodifiableList(newList);
    }

    /**
     * Creates a filtered query that does not do any sorting.
     * 
     * @see #createFilteredQuery(java.lang.String, java.lang.String, com.vaadin.addons.jpacontainer.Filter, java.util.List, boolean, com.vaadin.addons.jpacontainer.Filter.PropertyIdPreprocessor)
     * @param fieldsToSelect the fields to select (must not be null).
     * @param entityAlias the alias of the entity (must not be null).
     * @param filter the filter to apply, or null if no filters should be applied.
     * @param propertyIdPreprocessor the property ID preprocessor (may be null).
     * @return the query (never null).
     */
    protected Query createUnsortedFilteredQuery(String fieldsToSelect,
            String entityAlias, Filter filter,
            PropertyIdPreprocessor propertyIdPreprocessor) {
        return createFilteredQuery(fieldsToSelect, entityAlias, filter, null,
                false, propertyIdPreprocessor);
    }

    /**
     * Creates a filtered, optionally sorted, query.
     *
     * @param fieldsToSelect the fields to select (must not be null).
     * @param entityAlias the alias of the entity (must not be null).
     * @param filter the filter to apply, or null if no filters should be applied.
     * @param sortBy the fields to sort by (must include at least one field), or null if the result should not be sorted at all.
     * @param swapSortOrder true to swap the sort order, false to use the sort order specified in <code>sortBy</code>. Only applies if <code>sortBy</code> is not null.
     * @param propertyIdPreprocessor the property ID preprocessor to pass to {@link Filter#toQLString(com.vaadin.addons.jpacontainer.filter.PropertyIdPreprocessor) },
     * or null to use a default preprocessor (should be sufficient in most cases).
     * @return the query (never null).
     */
    protected Query createFilteredQuery(String fieldsToSelect,
            final String entityAlias, Filter filter, List<SortBy> sortBy,
            boolean swapSortOrder,
            PropertyIdPreprocessor propertyIdPreprocessor) {
        assert fieldsToSelect != null : "fieldsToSelect must not be null";
        assert entityAlias != null : "entityAlias must not be null";
        assert sortBy == null || !sortBy.isEmpty() :
                "sortBy must be either null or non-empty";

        StringBuffer sb = new StringBuffer();
        sb.append("select ");
        sb.append(fieldsToSelect);
        sb.append(" from ");
        sb.append(getEntityClassMetadata().getEntityName());
        sb.append(" as ");
        sb.append(entityAlias);

        if (filter != null) {
            sb.append(" where ");

            if (propertyIdPreprocessor == null) {
                sb.append(filter.toQLString(new PropertyIdPreprocessor() {

                    @Override
                    public String process(Object propertyId) {
                        return entityAlias + "." + propertyId;
                    }
                }));
            } else {
                sb.append(filter.toQLString(propertyIdPreprocessor));
            }
        }

        if (sortBy != null) {
            sb.append(" order by ");
            for (Iterator<SortBy> it = sortBy.iterator(); it.hasNext();) {
                SortBy sortedProperty = it.next();
                sb.append(entityAlias);
                sb.append(".");
                sb.append(sortedProperty.propertyId);
                if (sortedProperty.ascending != swapSortOrder) {
                    sb.append(" asc");
                } else {
                    sb.append(" desc");
                }
                if (it.hasNext()) {
                    sb.append(", ");
                }
            }
        }

        String queryString = sb.toString();
        Query query = getEntityManager().createQuery(queryString);
        if (filter != null) {
            setQueryParameters(query, filter);
        }
        return query;
    }

    private void setQueryParameters(Query query, Filter filter) {
        // TODO Add test that detects if any specific filter type is missing!
        if (filter instanceof ValueFilter) {
            ValueFilter vf = (ValueFilter) filter;
            query.setParameter(vf.getQLParameterName(), vf.getValue());
        } else if (filter instanceof IntervalFilter) {
            IntervalFilter intf = (IntervalFilter) filter;
            query.setParameter(intf.getEndingPointQLParameterName(), intf.getEndingPoint());
            query.setParameter(intf.getStartingPointQLParameterName(), intf.getStartingPoint());
        } else if (filter instanceof CompositeFilter) {
            for (Filter f : ((CompositeFilter) filter).getFilters()) {
                setQueryParameters(query, f);
            }
        }
    }

    @Override
    public boolean containsEntity(Object entityId, Filter filter) {
        assert entityId != null : "entityId must not be null";
        Filter entityIdFilter = Filters.eq(getEntityClassMetadata().
                getIdentifierProperty().getName(), entityId);
        Filter f;
        if (filter == null) {
            f = entityIdFilter;
        } else {
            f = Filters.and(entityIdFilter, filter);
        }
        Query query = createUnsortedFilteredQuery("count(obj)", "obj", f,
                null);
        return ((Long) query.getSingleResult()) == 1;
    }

    @Override
    public T getEntity(Object entityId) {
        assert entityId != null : "entityId must not be null";
        T entity = getEntityManager().find(getEntityClassMetadata().
                getMappedClass(),
                entityId);
        return detachEntity(entity);
    }

    @Override
    public Object getEntityIdentifierAt(Filter filter,
            List<SortBy> sortBy, int index) {
        assert sortBy != null : "sortBy must not be null";
        Query query = createFilteredQuery("obj." + getEntityClassMetadata().
                getIdentifierProperty().getName(), "obj", filter, addPrimaryKeyToSortList(
                sortBy), false, null);
        query.setMaxResults(1);
        query.setFirstResult(index);
        List<?> result = query.getResultList();
        if (result.isEmpty()) {
            return null;
        } else {
            return result.get(0);
        }
    }

    @Override
    public int getEntityCount(Filter filter) {
        Query query = createUnsortedFilteredQuery("count(obj)", "obj", filter,
                null);
        return ((Long) query.getSingleResult()).intValue();
    }

    @Override
    public Object getFirstEntityIdentifier(Filter filter,
            List<SortBy> sortBy) {
        assert sortBy != null : "sortBy must not be null";
        Query query = createFilteredQuery("obj." + getEntityClassMetadata().
                getIdentifierProperty().getName(), "obj", filter, addPrimaryKeyToSortList(
                sortBy), false,
                null);
        query.setMaxResults(1);
        List<?> result = query.getResultList();
        if (result.isEmpty()) {
            return null;
        } else {
            return result.get(0);
        }
    }

    @Override
    public Object getLastEntityIdentifier(Filter filter,
            List<SortBy> sortBy) {
        assert sortBy != null : "sortBy must not be null";
        Query query = createFilteredQuery("obj." + getEntityClassMetadata().
                getIdentifierProperty().getName(), "obj", filter, addPrimaryKeyToSortList(
                sortBy), true,
                null);
        query.setMaxResults(1);
        List<?> result = query.getResultList();
        if (result.isEmpty()) {
            return null;
        } else {
            return result.get(0);
        }
    }

    /**
     * If <code>backwards</code> is false, this method will return the identifier of the entity
     * next to the entity identified by <code>entityId</code>. If true, this method will return the identifier
     * of the entity previous to the entity identified by <code>entityId</code>. <code>filter</code> and <code>sortBy</code>
     * is used to define and limit the list of entities to be used for determining the sibling.
     *
     * @param entityId the identifier of the entity whose sibling to retrieve (must not be null).
     * @param filter an optional filter to limit the entities (may be null).
     * @param sortBy the order in which the list should be sorted (must not be null).
     * @param backwards true to fetch the previous sibling, false to fetch the next sibling.
     * @return the identifier of the "sibling".
     */
    protected Object getSibling(Object entityId, Filter filter,
            List<SortBy> sortBy, boolean backwards) {
        assert entityId != null : "entityId must not be null";
        assert sortBy != null : "sortBy must not be null";
        Filter limitingFilter;
        sortBy = addPrimaryKeyToSortList(sortBy);
        if (sortBy.size() == 1) {
            // The list is sorted by primary key
            if (backwards) {
                limitingFilter = Filters.lt(getEntityClassMetadata().
                        getIdentifierProperty().getName(), entityId);
            } else {
                limitingFilter = Filters.gt(getEntityClassMetadata().
                        getIdentifierProperty().getName(), entityId);
            }
        } else {
            // We have to fetch the values of the sorted fields
            T currentEntity = getEntity(entityId);
            if (currentEntity == null) {
                throw new EntityNotFoundException("No entity found with the ID "
                        + entityId);
            }
            // Collect the values into a map for easy access
            Map<Object, Object> filterValues = new HashMap<Object, Object>();
            for (SortBy sb : sortBy) {
                filterValues.put(sb.propertyId, getEntityClassMetadata().
                        getPropertyValue(
                        currentEntity, sb.propertyId.toString()));
            }
            // Now we can build a filter that limits the query to the entities
            // below entityId
            limitingFilter = Filters.or();
            for (int i = sortBy.size() - 1; i >= 0; i--) {
                // TODO Document this code snippet once it works
                // TODO What happens with null values?
                Junction caseFilter = Filters.and();
                SortBy sb;
                for (int j = 0; j < i; j++) {
                    sb = sortBy.get(j);
                    caseFilter.add(Filters.eq(sb.propertyId, filterValues.get(
                            sb.propertyId)));
                }
                sb = sortBy.get(i);
                if (sb.ascending ^ backwards) {
                    caseFilter.add(Filters.gt(sb.propertyId, filterValues.get(
                            sb.propertyId)));
                } else {
                    caseFilter.add(Filters.lt(sb.propertyId, filterValues.get(
                            sb.propertyId)));
                }
                ((Junction) limitingFilter).add(caseFilter);
            }
        }
        // Now, we execute the query
        Filter queryFilter;
        if (filter == null) {
            queryFilter = limitingFilter;
        } else {
            queryFilter = Filters.and(filter, limitingFilter);
        }
        Query query = createFilteredQuery("obj." + getEntityClassMetadata().
                getIdentifierProperty().getName(), "obj", queryFilter, sortBy,
                backwards, null);
        query.setMaxResults(1);
        List<?> result = query.getResultList();
        if (result.size() != 1) {
            return null;
        } else {
            return result.get(0);
        }
    }

    @Override
    public Object getNextEntityIdentifier(Object entityId, Filter filter,
            List<SortBy> sortBy) {
        return getSibling(entityId, filter, sortBy, false);
    }

    @Override
    public Object getPreviousEntityIdentifier(Object entityId, Filter filter,
            List<SortBy> sortBy) {
        return getSibling(entityId, filter, sortBy, true);
    }
    private boolean transactionsHandled = false;

    /**
     * Specifies whether the entity provider should handle transactions
     * itself or whether they should be handled outside (e.g. if declarative
     * transactions are used).
     * 
     * @param transactionsHandled true to handle the transactions internally,
     * false to rely on external transaction handling.
     */
    public void setTransactionsHandled(boolean transactionsHandled) {
        this.transactionsHandled = transactionsHandled;
    }

    /**
     * Returns whether the entity provider is handling transactions internally
     * or relies on external transaction handling (the default).
     *
     * @return true if transactions are handled internally, false if not.
     */
    public boolean isTransactionsHandled() {
        return transactionsHandled;
    }

    /**
     * If {@link #isTransactionsHandled() } is true, <code>operation</code> will
     * be executed inside a transaction that is commited after the operation is completed.
     * Otherwise, <code>operation</code> will just be executed.
     * 
     * @param operation the operation to run (must not be null).
     */
    protected void runInTransaction(Runnable operation) {
        assert operation != null : "operation must not be null";
        if (isTransactionsHandled()) {
            EntityTransaction et = getEntityManager().getTransaction();
            try {
                et.begin();
                operation.run();
                et.commit();
            } finally {
                if (et.isActive()) {
                    et.rollback();
                }
            }
        } else {
            operation.run();
        }
    }

    /**
     * Detaches <code>entity</code> from the entity manager (until JPA 2.0 arrives).
     * If <code>entity</code> is null, then null is returned.
     *
     * @param entity the entity to detach.
     * @return the detached entity.
     */
    protected T detachEntity(T entity) {
        if (entity == null) {
            return null;
        }
        // TODO Replace with more efficient code, or a call to JPA 2.0
        if (entity instanceof Serializable) {
            /*
             * What we do here is basically a clone, but we are using
             * the Java serialization API. Thus, the entity parameter
             * will be managed, but the returned entity will be a detached
             * exact (well, more or less) copy of the entity.
             *
             * As of JPA 2.0, we can remove this code and just ask JPA
             * to detach the object for us.
             */
            try {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(os);
                oos.writeObject(entity);
                ByteArrayInputStream is = new ByteArrayInputStream(os.
                        toByteArray());
                ObjectInputStream ois = new ObjectInputStream(is);
                return getEntityClassMetadata().getMappedClass().cast(ois.
                        readObject());
            } catch (Exception e) {
                // Do nothing, entity manager will be cleared
            }
        }
        System.out.println(
                "WARNING: Clearing EntityManager in order to detach the entities in it");
        getEntityManager().clear();
        return entity;
    }

    @Override
    public T addEntity(final T entity) {
        assert entity != null;
        runInTransaction(new Runnable() {

            @Override
            public void run() {
                EntityManager em = getEntityManager();
                em.persist(entity);
                em.flush();
            }
        });
        return detachEntity(entity);
    }

    @Override
    public void removeEntity(final Object entityId) {
        assert entityId != null;
        runInTransaction(new Runnable() {

            @Override
            public void run() {
                EntityManager em = getEntityManager();
                T entity = em.find(getEntityClassMetadata().getMappedClass(),
                        entityId);
                if (entity != null) {
                    em.remove(entity);
                    em.flush();
                }
            }
        });
    }

    @Override
    public T updateEntity(final T entity) {
        assert entity != null : "entity must not be null";
        runInTransaction(new Runnable() {

            @Override
            public void run() {
                EntityManager em = getEntityManager();
                em.merge(entity);
                em.flush();
            }
        });
        return detachEntity(entity);
    }

    @Override
    public void updateEntityProperty(final Object entityId,
            final String propertyName,
            final Object propertyValue) throws IllegalArgumentException {
        assert entityId != null : "entityId must not be null";
        assert propertyName != null : "propertyName must not be null";
        runInTransaction(new Runnable() {

            @Override
            public void run() {
                EntityManager em = getEntityManager();
                T entity = em.find(getEntityClassMetadata().getMappedClass(),
                        entityId);
                if (entity != null) {
                    getEntityClassMetadata().setPropertyValue(entity,
                            propertyName, propertyValue);
                    em.flush();
                }
            }
        });
    }
}