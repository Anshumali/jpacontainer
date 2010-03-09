/*
 * JPAContainer
 * Copyright (C) 2010 Oy IT Mill Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.vaadin.addon.jpacontainer.provider.emtests.hibernate;

import com.vaadin.addon.jpacontainer.provider.MutableLocalEntityProvider;
import com.vaadin.addon.jpacontainer.provider.emtests.AbstractMutableLocalEntityProviderEMTest;
import com.vaadin.addon.jpacontainer.testdata.Address;
import com.vaadin.addon.jpacontainer.testdata.EmbeddedIdPerson;
import com.vaadin.addon.jpacontainer.testdata.Name;
import com.vaadin.addon.jpacontainer.testdata.Person;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import org.hibernate.ejb.Ejb3Configuration;

/**
 * Entity Manager test for {@link MutableLocalEntityProvider} that uses
 * Hibernate as the entity manager implementation.
 * 
 * @author Petter Holmström (IT Mill)
 * @since 1.0
 */
public class MutableEntityProviderHibernateTest extends
		AbstractMutableLocalEntityProviderEMTest {

	private EntityManager entityManager;

	private void setupEntityManager() throws Exception {
		Ejb3Configuration cfg = new Ejb3Configuration().setProperty(
				"hibernate.dialect", "org.hibernate.dialect.HSQLDialect")
				.setProperty("hibernate.connection.driver_class",
						"org.hsqldb.jdbcDriver").setProperty(
						"hibernate.connection.url",
						"jdbc:hsqldb:mem:integrationtest").setProperty(
						"hibernate.connection.username", "sa").setProperty(
						"hibernate.connection.password", "").setProperty(
						"hibernate.connection.pool_size", "1").setProperty(
						"hibernate.connection.autocommit", "true").setProperty(
						"hibernate.cache.provider_class",
						"org.hibernate.cache.HashtableCacheProvider")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop")
				.setProperty("hibernate.show_sql", "false").addAnnotatedClass(
						Person.class).addAnnotatedClass(Address.class).addAnnotatedClass(
				EmbeddedIdPerson.class).addAnnotatedClass(Name.class);
		EntityManagerFactory emf = cfg.buildEntityManagerFactory();
		entityManager = emf.createEntityManager();
	}

	@Override
	protected EntityManager getEntityManager() throws Exception {
		if (entityManager == null) {
			setupEntityManager();
		}
		return entityManager;
	}
}