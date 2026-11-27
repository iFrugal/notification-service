package com.lazydevs.notification.rest.template;

import jakarta.servlet.http.HttpServletRequest;

import java.security.Principal;
import java.util.Optional;

/**
 * Names who is calling the template admin API, for the audit trail (DD-38). The library
 * never authenticates: the host's security decides who may call, and this hook only reads
 * the result. The default reads {@link HttpServletRequest#getUserPrincipal()}; declare a
 * bean of this type to read a gateway header or a token claim instead.
 *
 * <p>A write without an actor is refused with HTTP 401.
 *
 * @since 1.3.0
 */
@FunctionalInterface
public interface ActorResolver {

    /** The actor's name, empty when the request carries none. */
    Optional<String> resolve(HttpServletRequest request);

    /** The authenticated principal's name. */
    static ActorResolver principal() {
        return request -> Optional.ofNullable(request.getUserPrincipal())
                .map(Principal::getName)
                .filter(name -> !name.isBlank());
    }
}
