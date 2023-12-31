package org.keycloak.url;

import static org.keycloak.common.util.UriUtils.checkUrl;
import static org.keycloak.utils.StringUtil.isNotBlank;

import org.jboss.logging.Logger;
import org.keycloak.common.enums.SslRequired;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.urls.HostnameProvider;
import org.keycloak.urls.UrlType;

import jakarta.ws.rs.core.UriInfo;
import java.net.URI;

public class DefaultHostnameProvider implements HostnameProvider {

    private static final Logger LOGGER = Logger.getLogger(DefaultHostnameProvider.class);

    private final KeycloakSession session;

    private final URI frontendUri;

    private String currentRealm;

    private URI realmUri;

    private URI adminUri;

    private URI localAdminUri = URI.create("http://localhost:8080/auth");

    private final boolean forceBackendUrlToFrontendUrl;

    public DefaultHostnameProvider(KeycloakSession session, URI frontendUri, URI adminUri, boolean forceBackendUrlToFrontendUrl) {
        this.session = session;
        this.frontendUri = frontendUri;
        this.adminUri = adminUri;
        this.forceBackendUrlToFrontendUrl = forceBackendUrlToFrontendUrl;
    }

    @Override
    public String getScheme(UriInfo originalUriInfo, UrlType type) {
        return resolveUri(originalUriInfo, type).getScheme();
    }

    @Override
    public String getHostname(UriInfo originalUriInfo, UrlType type) {
        return resolveUri(originalUriInfo, type).getHost();
    }

    @Override
    public int getPort(UriInfo originalUriInfo, UrlType type) {
        return resolveUri(originalUriInfo, type).getPort();
    }

    @Override
    public String getContextPath(UriInfo originalUriInfo, UrlType type) {
        return resolveUri(originalUriInfo, type).getPath();
    }

    private URI resolveUri(UriInfo originalUriInfo, UrlType type) {
        if (type.equals(UrlType.LOCAL_ADMIN)) {
            return localAdminUri;
        }

        URI realmUri = getRealmUri();
        URI frontendUri = realmUri != null ? realmUri : this.frontendUri;

        // Use frontend URI for backend requests if forceBackendUrlToFrontendUrl is true
        if (type.equals(UrlType.BACKEND) && forceBackendUrlToFrontendUrl) {
            type = UrlType.FRONTEND;
        }

        // Use frontend URI for backend requests if request hostname matches frontend hostname
        if (type.equals(UrlType.BACKEND) && frontendUri != null && originalUriInfo.getBaseUri().getHost().equals(frontendUri.getHost())) {
            type = UrlType.FRONTEND;
        }

        // Use frontend URI for admin requests if adminUrl not set
        if (type.equals(UrlType.ADMIN)) {
            if (adminUri != null) {
                return adminUri;
            } else {
                type = UrlType.FRONTEND;
            }
        }

        if (type.equals(UrlType.FRONTEND) && frontendUri != null) {
            return frontendUri;
        }

        return originalUriInfo.getBaseUri();
    }

    private URI getRealmUri() {
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            currentRealm = null;
            realmUri = null;

            return null;
        } else if (realm.getId().equals(currentRealm)) {
            return realmUri;
        } else {
            currentRealm = realm.getId();
            realmUri = null;

            String realmFrontendUrl = session.getContext().getRealm().getAttribute("frontendUrl");
            if (isNotBlank(realmFrontendUrl)) {
                try {
                    checkUrl(SslRequired.NONE, realmFrontendUrl, "frontendUrl");
                    realmUri = URI.create(realmFrontendUrl);
                } catch (IllegalArgumentException e) {
                    LOGGER.errorf(e, "Failed to parse realm frontendUrl '%s'. Falling back to global value.", realmFrontendUrl);
                }
            }

            return realmUri;
        }
    }

}
