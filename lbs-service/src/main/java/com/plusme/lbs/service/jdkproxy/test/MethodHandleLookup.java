package com.plusme.lbs.service.jdkproxy.test;

import org.springframework.util.ReflectionUtils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

/**
 * @author plusme
 * @create 2019-12-14 12:00
 */
public enum MethodHandleLookup {

    /**
     * Open (via reflection construction of {@link MethodHandles.Lookup}) method handle lookup. Works with Java 8 and
     * with Java 9 permitting illegal access.
     */
    OPEN {

        private final Optional<Constructor<MethodHandles.Lookup>> constructor = getLookupConstructor();

        /*
         * (non-Javadoc)
         * @see org.springframework.data.projection.DefaultMethodInvokingMethodInterceptor.MethodHandleLookup#lookup(java.lang.reflect.Method)
         */
        @Override
        MethodHandle lookup(Method method) throws ReflectiveOperationException {

            Constructor<MethodHandles.Lookup> constructor = this.constructor
                    .orElseThrow(() -> new IllegalStateException("Could not obtain MethodHandles.lookup constructor"));

            return constructor.newInstance(method.getDeclaringClass()).unreflectSpecial(method, method.getDeclaringClass());
        }

        /*
         * (non-Javadoc)
         * @see org.springframework.data.projection.DefaultMethodInvokingMethodInterceptor.MethodHandleLookup#isAvailable()
         */
        @Override
        boolean isAvailable() {
            return constructor.isPresent();
        }
    },

    /**
     * Encapsulated {@link MethodHandle} lookup working on Java 9.
     */
    ENCAPSULATED {

        private final  Method privateLookupIn = ReflectionUtils.findMethod(MethodHandles.class,
                "privateLookupIn", Class.class, MethodHandles.Lookup.class);

        /*
         * (non-Javadoc)
         * @see org.springframework.data.projection.DefaultMethodInvokingMethodInterceptor.MethodHandleLookup#lookup(java.lang.reflect.Method)
         */
        @Override
        MethodHandle lookup(Method method) throws ReflectiveOperationException {

            MethodType methodType = MethodType.methodType(method.getReturnType(), method.getParameterTypes());

            return getLookup(method.getDeclaringClass(), privateLookupIn).findSpecial(method.getDeclaringClass(),
                    method.getName(), methodType, method.getDeclaringClass());
        }

        /*
         * (non-Javadoc)
         * @see org.springframework.data.projection.DefaultMethodInvokingMethodInterceptor.MethodHandleLookup#isAvailable()
         */
        @Override
        boolean isAvailable() {
            return true;
        }

        private MethodHandles.Lookup getLookup(Class<?> declaringClass, Method privateLookupIn) {

            if (privateLookupIn == null) {
                return MethodHandles.lookup();
            }

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            try {
                return (MethodHandles.Lookup) privateLookupIn.invoke(MethodHandles.class, declaringClass, lookup);
            } catch (ReflectiveOperationException e) {
                return lookup;
            }
        }
    };

    /**
     * Lookup a {@link MethodHandle} given {@link Method} to look up.
     *
     * @param method must not be {@literal null}.
     * @return the method handle.
     * @throws ReflectiveOperationException
     */
    abstract MethodHandle lookup(Method method) throws ReflectiveOperationException;

    /**
     * @return {@literal true} if the lookup is available.
     */
    abstract boolean isAvailable();

    /**
     * Obtain the first available {@link MethodHandleLookup}.
     *
     * @return the {@link MethodHandleLookup}
     * @throws IllegalStateException if no {@link MethodHandleLookup} is available.
     */
    public static MethodHandleLookup getMethodHandleLookup() {

        return Arrays.stream(MethodHandleLookup.values()) //
                .filter(it -> it.isAvailable()) //
                .findFirst() //
                .orElseThrow(() -> new IllegalStateException("No MethodHandleLookup available!"));
    }

    private static Optional<Constructor<MethodHandles.Lookup>> getLookupConstructor() {

        try {

            Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class);
            ReflectionUtils.makeAccessible(constructor);

            return Optional.of(constructor);

        } catch (Exception ex) {

            // this is the signal that we are on Java 9 (encapsulated) and can't use the accessible constructor approach.
            if (ex.getClass().getName().equals("java.lang.reflect.InaccessibleObjectException")) {
                return Optional.empty();
            }

            throw new IllegalStateException(ex);
        }
    }
}