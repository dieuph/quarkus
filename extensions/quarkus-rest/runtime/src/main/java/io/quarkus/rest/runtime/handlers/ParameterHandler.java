package io.quarkus.rest.runtime.handlers;

import java.util.function.BiConsumer;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;

import io.quarkus.rest.runtime.core.QuarkusRestRequestContext;
import io.quarkus.rest.runtime.core.parameters.ParameterExtractor;
import io.quarkus.rest.runtime.core.parameters.converters.ParameterConverter;
import io.quarkus.rest.runtime.model.ParameterType;
import io.quarkus.rest.runtime.util.QuarkusRestUtil;

public class ParameterHandler implements RestHandler {

    private final int index;
    private final String defaultValue;
    private final ParameterExtractor extractor;
    private final ParameterConverter converter;
    private final ParameterType parameterType;

    public ParameterHandler(int index, String defaultValue, ParameterExtractor extractor, ParameterConverter converter,
            ParameterType parameterType) {
        this.index = index;
        this.defaultValue = defaultValue;
        this.extractor = extractor;
        this.converter = converter;
        this.parameterType = parameterType;
    }

    @Override
    public void handle(QuarkusRestRequestContext requestContext) {
        try {
            Object result = extractor.extractParameter(requestContext);
            if (result instanceof ParameterExtractor.ParameterCallback) {
                requestContext.suspend();
                ((ParameterExtractor.ParameterCallback) result).setListener(new BiConsumer<Object, Exception>() {
                    @Override
                    public void accept(Object o, Exception throwable) {
                        if (throwable != null) {
                            requestContext.resume(throwable);
                        } else {
                            handleResult(o, requestContext, true);
                            requestContext.resume();
                        }
                    }
                });
            } else {
                handleResult(result, requestContext, false);
            }
        } catch (Exception e) {
            if (e instanceof WebApplicationException) {
                throw e;
            } else {
                throw new WebApplicationException(e, 400);
            }
        }
    }

    private void handleResult(Object result, QuarkusRestRequestContext requestContext, boolean needsResume) {
        if (result == null) {
            result = defaultValue;
        }
        Throwable toThrow = null;
        if (converter != null && result != null) {
            // spec says: 
            /*
             * 3.2 Fields and Bean Properties
             * if the field or property is annotated with @MatrixParam, @QueryParam or @PathParam then an implementation
             * MUST generate an instance of NotFoundException (404 status) that wraps the thrown exception and no
             * entity; if the field or property is annotated with @HeaderParam or @CookieParam then an implementation
             * MUST generate an instance of BadRequestException (400 status) that wraps the thrown exception and
             * no entity.
             */
            switch (parameterType) {
                case COOKIE:
                case HEADER:
                    try {
                        result = converter.convert(result);
                    } catch (WebApplicationException x) {
                        toThrow = x;
                    } catch (Throwable x) {
                        toThrow = new BadRequestException(x);
                    }
                    break;
                case MATRIX:
                case PATH:
                case QUERY:
                    try {
                        result = converter.convert(result);
                    } catch (WebApplicationException x) {
                        toThrow = x;
                    } catch (Throwable x) {
                        toThrow = new NotFoundException(x);
                    }
                    break;
                default:
                    try {
                        result = converter.convert(result);
                    } catch (Throwable x) {
                        toThrow = x;
                    }
                    break;
            }
        }
        if (needsResume) {
            if(toThrow == null) {
                requestContext.resume();
            } else {
                requestContext.resume(toThrow);
            }
        } else if(toThrow != null){
            throw QuarkusRestUtil.sneakyThrow(toThrow);
        }
    }
}
