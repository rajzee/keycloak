/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.models.jpa;

import org.hibernate.exception.ConstraintViolationException;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.ModelException;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.function.Predicate;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class PersistenceExceptionConverter implements InvocationHandler {

    private EntityManager em;

    public static EntityManager create(EntityManager em) {
        return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(), new Class[]{EntityManager.class}, new PersistenceExceptionConverter(em));
    }

    private PersistenceExceptionConverter(EntityManager em) {
        this.em = em;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(em, args);
        } catch (InvocationTargetException e) {
            throw convert(e.getCause());
        }
    }

    // For JTA, the database operations are executed during the commit phase of a transaction, and DB exceptions can be propagated differently
    public static ModelException convert(Throwable t) {
        final Predicate<Throwable> checkDuplicationMessage = throwable -> {
            final String message = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
            return message.toLowerCase().contains("duplicate");
        };

        Predicate<Throwable> throwModelDuplicateEx = throwable ->
                throwable instanceof EntityExistsException
                        || throwable instanceof ConstraintViolationException
                        || throwable instanceof SQLIntegrityConstraintViolationException;

        throwModelDuplicateEx = throwModelDuplicateEx.or(checkDuplicationMessage);

        if (t.getCause() != null && throwModelDuplicateEx.test(t.getCause())) {
            throw new ModelDuplicateException(t.getCause());
        } else if (throwModelDuplicateEx.test(t)) {
            throw new ModelDuplicateException(t);
        } else {
            throw new ModelException(t);
        }
    }

}
