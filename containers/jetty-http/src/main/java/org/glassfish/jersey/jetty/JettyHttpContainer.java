/*
 * Copyright (c) 2013, 2023 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.jetty;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.Application;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.internal.inject.ReferencingFactory;
import org.glassfish.jersey.internal.util.ExtendedLogger;
import org.glassfish.jersey.internal.util.collection.Ref;
import org.glassfish.jersey.jetty.internal.LocalizationMessages;
import org.glassfish.jersey.process.internal.RequestScoped;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerException;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.internal.ContainerUtils;
import org.glassfish.jersey.server.spi.Container;
import org.glassfish.jersey.server.spi.ContainerResponseWriter;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.AbstractHandler;

/**
 * Jersey {@code Container} implementation based on Jetty {@link org.eclipse.jetty.server.Handler}.
 *
 * @author Arul Dhesiaseelan (aruld@acm.org)
 * @author Libor Kramolis
 * @author Marek Potociar
 */
public final class JettyHttpContainer extends AbstractHandler implements Container {

    private static final ExtendedLogger LOGGER =
            new ExtendedLogger(Logger.getLogger(JettyHttpContainer.class.getName()), Level.FINEST);

    private static final Type REQUEST_TYPE = (new GenericType<Ref<Request>>() {}).getType();
    private static final Type RESPONSE_TYPE = (new GenericType<Ref<Response>>() {}).getType();

    private static final int INTERNAL_SERVER_ERROR = Status.INTERNAL_SERVER_ERROR.getStatusCode();
    private static final Status BAD_REQUEST_STATUS = Status.BAD_REQUEST;

    /**
     * Cached value of configuration property
     * {@link org.glassfish.jersey.server.ServerProperties#RESPONSE_SET_STATUS_OVER_SEND_ERROR}.
     * If {@code true} method {@link HttpServletResponse#setStatus} is used over {@link HttpServletResponse#sendError}.
     */
    private boolean configSetStatusOverSendError;

    /**
     * Referencing factory for Jetty request.
     */
    private static class JettyRequestReferencingFactory extends ReferencingFactory<Request> {
        @Inject
        public JettyRequestReferencingFactory(final Provider<Ref<Request>> referenceFactory) {
            super(referenceFactory);
        }
    }

    /**
     * Referencing factory for Jetty response.
     */
    private static class JettyResponseReferencingFactory extends ReferencingFactory<Response> {
        @Inject
        public JettyResponseReferencingFactory(final Provider<Ref<Response>> referenceFactory) {
            super(referenceFactory);
        }
    }

    /**
     * An internal binder to enable Jetty HTTP container specific types injection.
     * This binder allows to inject underlying Jetty HTTP request and response instances.
     * Note that since Jetty {@code Request} class is not proxiable as it does not expose an empty constructor,
     * the injection of Jetty request instance into singleton JAX-RS and Jersey providers is only supported via
     * {@link javax.inject.Provider injection provider}.
     */
    private static class JettyBinder extends AbstractBinder {

        @Override
        protected void configure() {
            bindFactory(JettyRequestReferencingFactory.class).to(Request.class)
                    .proxy(false).in(RequestScoped.class);
            bindFactory(ReferencingFactory.<Request>referenceFactory()).to(new GenericType<Ref<Request>>() {})
                    .in(RequestScoped.class);

            bindFactory(JettyResponseReferencingFactory.class).to(Response.class)
                    .proxy(false).in(RequestScoped.class);
            bindFactory(ReferencingFactory.<Response>referenceFactory()).to(new GenericType<Ref<Response>>() {})
                    .in(RequestScoped.class);
        }
    }

    private volatile ApplicationHandler appHandler;

    @Override
    public void handle(final String target, final Request request, final HttpServletRequest httpServletRequest,
                       final HttpServletResponse httpServletResponse) throws IOException, ServletException {

        if (request.isHandled()) {
            return;
        }

        final Response response = request.getResponse();
        final ResponseWriter responseWriter = new ResponseWriter(request, response, configSetStatusOverSendError);
        try {
            final URI baseUri = getBaseUri(request);
            final URI requestUri = getRequestUri(request, baseUri);
            final ContainerRequest requestContext = new ContainerRequest(
                    baseUri,
                    requestUri,
                    request.getMethod(),
                    getSecurityContext(request),
                    new MapPropertiesDelegate(),
                    appHandler.getConfiguration());
            requestContext.setEntityStream(request.getInputStream());
            final Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                final String headerName = headerNames.nextElement();
                String headerValue = request.getHeader(headerName);
                requestContext.headers(headerName, headerValue == null ? "" : headerValue);
            }
            requestContext.setWriter(responseWriter);
            requestContext.setRequestScopedInitializer(injectionManager -> {
                injectionManager.<Ref<Request>>getInstance(REQUEST_TYPE).set(request);
                injectionManager.<Ref<Response>>getInstance(RESPONSE_TYPE).set(response);
            });

            // Mark the request as handled before generating the body of the response
            request.setHandled(true);
            appHandler.handle(requestContext);
        } catch (URISyntaxException e) {
            setResponseForInvalidUri(response, e);
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private URI getRequestUri(final Request request, final URI baseUri) throws URISyntaxException {
        final String serverAddress = getServerAddress(baseUri);
        String uri = request.getRequestURI();

        final String queryString = request.getQueryString();
        if (queryString != null) {
            uri = uri + "?" + ContainerUtils.encodeUnsafeCharacters(queryString);
        }

        return new URI(serverAddress + uri);
    }

    private void setResponseForInvalidUri(final HttpServletResponse response, final Throwable throwable) throws IOException {
        LOGGER.log(Level.FINER, "Error while processing request.", throwable);

        if (configSetStatusOverSendError) {
            response.reset();
            //noinspection deprecation
            response.setStatus(BAD_REQUEST_STATUS.getStatusCode(), BAD_REQUEST_STATUS.getReasonPhrase());
        } else {
            response.sendError(BAD_REQUEST_STATUS.getStatusCode(), BAD_REQUEST_STATUS.getReasonPhrase());
        }
    }

    private String getServerAddress(URI baseUri) {
        String serverAddress = baseUri.toString();
        if (serverAddress.charAt(serverAddress.length() - 1) == '/') {
            return serverAddress.substring(0, serverAddress.length() - 1);
        }
        return serverAddress;
    }

    private SecurityContext getSecurityContext(final Request request) {
        return new SecurityContext() {

            @Override
            public boolean isUserInRole(final String role) {
                return request.isUserInRole(role);
            }

            @Override
            public boolean isSecure() {
                return request.isSecure();
            }

            @Override
            public Principal getUserPrincipal() {
                return request.getUserPrincipal();
            }

            @Override
            public String getAuthenticationScheme() {
                return request.getAuthType();
            }
        };
    }


    private URI getBaseUri(final Request request) throws URISyntaxException {
        return new URI(request.getScheme(), null, request.getServerName(),
                request.getServerPort(), getBasePath(request), null, null);
    }

    private String getBasePath(final Request request) {
        final String contextPath = request.getContextPath();

        if (contextPath == null || contextPath.isEmpty()) {
            return "/";
        } else if (contextPath.charAt(contextPath.length() - 1) != '/') {
            return contextPath + "/";
        } else {
            return contextPath;
        }
    }

    private static final class ResponseWriter implements ContainerResponseWriter {

        private final Response response;
        private final Continuation continuation;
        private final boolean configSetStatusOverSendError;

        ResponseWriter(final Request request, final Response response, final boolean configSetStatusOverSendError) {
            this.response = response;
            this.continuation = ContinuationSupport.getContinuation(request);
            this.configSetStatusOverSendError = configSetStatusOverSendError;
        }

        @Override
        public OutputStream writeResponseStatusAndHeaders(final long contentLength, final ContainerResponse context)
                throws ContainerException {

            final javax.ws.rs.core.Response.StatusType statusInfo = context.getStatusInfo();

            final int code = statusInfo.getStatusCode();
            final String reason = statusInfo.getReasonPhrase() == null
                    ? HttpStatus.getMessage(code) : statusInfo.getReasonPhrase();

            response.setStatusWithReason(code, reason);

            if (contentLength != -1 && contentLength < Integer.MAX_VALUE) {
                response.setContentLength((int) contentLength);
            }
            for (final Map.Entry<String, List<String>> e : context.getStringHeaders().entrySet()) {
                for (final String value : e.getValue()) {
                    response.addHeader(e.getKey(), value);
                }
            }

            try {
                return response.getOutputStream();
            } catch (final IOException ioe) {
                throw new ContainerException("Error during writing out the response headers.", ioe);
            }
        }

        @Override
        public boolean suspend(final long timeOut, final TimeUnit timeUnit, final TimeoutHandler timeoutHandler) {
            try {
                if (timeOut > 0) {
                    final long timeoutMillis = TimeUnit.MILLISECONDS.convert(timeOut, timeUnit);
                    continuation.setTimeout(timeoutMillis);
                }
                continuation.addContinuationListener(new ContinuationListener() {
                    @Override
                    public void onComplete(final Continuation continuation) {
                    }

                    @Override
                    public void onTimeout(final Continuation continuation) {
                        if (timeoutHandler != null) {
                            timeoutHandler.onTimeout(ResponseWriter.this);
                        }
                    }
                });
                continuation.suspend(response);
                return true;
            } catch (final Exception ex) {
                return false;
            }
        }

        @Override
        public void setSuspendTimeout(final long timeOut, final TimeUnit timeUnit) throws IllegalStateException {
            if (timeOut > 0) {
                final long timeoutMillis = TimeUnit.MILLISECONDS.convert(timeOut, timeUnit);
                continuation.setTimeout(timeoutMillis);
            }
        }

        @Override
        public void commit() {
            try {
                closeOutput(response);
            } catch (final IOException e) {
                LOGGER.log(Level.WARNING, LocalizationMessages.UNABLE_TO_CLOSE_RESPONSE(), e);
            } finally {
                if (continuation.isSuspended()) {
                    continuation.complete();
                }
                LOGGER.log(Level.FINEST, "commit() called");
            }
        }

        private void closeOutput(Response response) throws IOException {
            try {
                response.completeOutput();
            } catch (final IOException e) {
                throw e;
            } catch (NoSuchMethodError e) {
                // try older Jetty Response#closeOutput
                try {
                    Method method = response.getClass().getMethod("closeOutput");
                    method.invoke(response);
                } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException ex) {
                    throw new IOException(ex);
                }
            }
        }

        @Override
        public void failure(final Throwable error) {
            try {
                if (!response.isCommitted()) {
                    try {
                        if (configSetStatusOverSendError) {
                            response.reset();
                            //noinspection deprecation
                            response.setStatus(INTERNAL_SERVER_ERROR, "Request failed.");
                        } else {
                            response.sendError(INTERNAL_SERVER_ERROR, "Request failed.");
                        }
                    } catch (final IllegalStateException ex) {
                        // a race condition externally committing the response can still occur...
                        LOGGER.log(Level.FINER, "Unable to reset failed response.", ex);
                    } catch (final IOException ex) {
                        throw new ContainerException(LocalizationMessages.EXCEPTION_SENDING_ERROR_RESPONSE(INTERNAL_SERVER_ERROR,
                                "Request failed."), ex);
                    }
                }
            } finally {
                LOGGER.log(Level.FINEST, "failure(...) called");
                commit();
                rethrow(error);
            }
        }

        @Override
        public boolean enableResponseBuffering() {
            return false;
        }

        /**
         * Rethrow the original exception as required by JAX-RS, 3.3.4.
         *
         * @param error throwable to be re-thrown
         */
        private void rethrow(final Throwable error) {
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            } else {
                throw new ContainerException(error);
            }
        }

    }

    @Override
    public ResourceConfig getConfiguration() {
        return appHandler.getConfiguration();
    }

    @Override
    public void reload() {
        reload(new ResourceConfig(getConfiguration()));
    }

    @Override
    public void reload(final ResourceConfig configuration) {
        appHandler.onShutdown(this);

        appHandler = new ApplicationHandler(configuration.register(new JettyBinder()));
        appHandler.onReload(this);
        appHandler.onStartup(this);
        cacheConfigSetStatusOverSendError();
    }

    @Override
    public ApplicationHandler getApplicationHandler() {
        return appHandler;
    }

    /**
     * Inform this container that the server has been started.
     * This method must be implicitly called after the server containing this container is started.
     *
     * @throws java.lang.Exception if a problem occurred during server startup.
     */
    @Override
    protected void doStart() throws Exception {
        super.doStart();
        appHandler.onStartup(this);
    }

    /**
     * Inform this container that the server is being stopped.
     * This method must be implicitly called before the server containing this container is stopped.
     *
     * @throws java.lang.Exception if a problem occurred during server shutdown.
     */
    @Override
    public void doStop() throws Exception {
        super.doStop();
        appHandler.onShutdown(this);
        appHandler = null;
    }

    /**
     * Create a new Jetty HTTP container.
     *
     * @param application   JAX-RS / Jersey application to be deployed on Jetty HTTP container.
     * @param parentContext DI provider specific context with application's registered bindings.
     */
    JettyHttpContainer(final Application application, final Object parentContext) {
        this.appHandler = new ApplicationHandler(application, new JettyBinder(), parentContext);
    }

    /**
     * Create a new Jetty HTTP container.
     *
     * @param application JAX-RS / Jersey application to be deployed on Jetty HTTP container.
     */
    JettyHttpContainer(final Application application) {
        this.appHandler = new ApplicationHandler(application, new JettyBinder());

        cacheConfigSetStatusOverSendError();
    }

    /**
     * The method reads and caches value of configuration property
     * {@link ServerProperties#RESPONSE_SET_STATUS_OVER_SEND_ERROR} for future purposes.
     */
    private void cacheConfigSetStatusOverSendError() {
        this.configSetStatusOverSendError = ServerProperties.getValue(getConfiguration().getProperties(),
                ServerProperties.RESPONSE_SET_STATUS_OVER_SEND_ERROR, false, Boolean.class);
    }

}
