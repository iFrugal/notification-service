package com.lazydevs.notification.store.jdbc;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Objects;

/**
 * Picks the {@link DataSource} the JDBC stores use.
 *
 * <p>With {@code notification.store.jdbc.datasource-bean-name} set, that
 * bean is used. Otherwise the application's single DataSource (or the
 * {@code @Primary} one among several) is used. Anything else fails
 * startup with a message naming the property to set.
 */
public class JdbcStoreDataSourceResolver {

    private static final String PROPERTY = "notification.store.jdbc.datasource-bean-name";

    private final ListableBeanFactory beanFactory;

    public JdbcStoreDataSourceResolver(ListableBeanFactory beanFactory) {
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory");
    }

    /**
     * @param beanName the configured bean name; {@code null} or blank for the default DataSource
     */
    public DataSource resolve(String beanName) {
        if (beanName != null && !beanName.isBlank()) {
            String name = beanName.trim();
            try {
                return beanFactory.getBean(name, DataSource.class);
            } catch (NoSuchBeanDefinitionException e) {
                throw new IllegalStateException("notification.store.type=jdbc: no DataSource bean named '"
                        + name + "' (from " + PROPERTY + "). Available DataSource beans: "
                        + available() + ".", e);
            }
        }
        try {
            return beanFactory.getBean(DataSource.class);
        } catch (NoUniqueBeanDefinitionException e) {
            throw new IllegalStateException("notification.store.type=jdbc: found several DataSource beans "
                    + available() + " and none is @Primary. Set " + PROPERTY + " to pick one.", e);
        } catch (NoSuchBeanDefinitionException e) {
            throw new IllegalStateException("notification.store.type=jdbc requires a DataSource bean, but"
                    + " none is defined. Configure spring.datasource.* (with a JDBC driver on the classpath)"
                    + " or declare a DataSource bean, and set " + PROPERTY + " if there are several.", e);
        }
    }

    private String available() {
        return Arrays.toString(BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, DataSource.class));
    }
}
