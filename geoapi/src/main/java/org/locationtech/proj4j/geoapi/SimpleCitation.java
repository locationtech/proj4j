/*
 * Copyright 2025, PROJ4J contributors
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
package org.locationtech.proj4j.geoapi;

import java.io.Serializable;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import org.opengis.metadata.Identifier;
import org.opengis.metadata.citation.Address;
import org.opengis.metadata.citation.Citation;
import org.opengis.metadata.citation.CitationDate;
import org.opengis.metadata.citation.Contact;
import org.opengis.metadata.citation.OnLineFunction;
import org.opengis.metadata.citation.OnlineResource;
import org.opengis.metadata.citation.PresentationForm;
import org.opengis.metadata.citation.ResponsibleParty;
import org.opengis.metadata.citation.Role;
import org.opengis.metadata.citation.Series;
import org.opengis.metadata.citation.Telephone;
import org.opengis.util.InternationalString;


/**
 * A citation containing only a title, an organization name and a URL.
 * This implementation merges many interfaces in a single class for convenience.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class SimpleCitation implements Citation, ResponsibleParty, Contact, OnlineResource, Serializable {
    /**
     * The citation for the PROJ4J software.
     */
    static final SimpleCitation PROJ4J = new SimpleCitation("PROJ4J", "Eclipse Foundation",
            "LocationTech", "https://projects.eclipse.org/projects/locationtech");

    /**
     * The title of the dataset or project.
     *
     * @see #getTitle()
     */
    private final String title;

    /**
     * The organization responsible for the maintenance of the dataset or project.
     *
     * @see #getOrganisationName()
     * @see #getCitedResponsibleParties()
     */
    private final String organization;

    /**
     * Name of the page referenced by {@link #url}.
     */
    private final String urlName;

    /**
     * URL where user can get more information.
     */
    private final String url;

    /**
     * Creates a new citation with the given title.
     */
    private SimpleCitation(final String title, final String organization, final String urlName, final String url) {
        this.title = title;
        this.organization = organization;
        this.urlName = urlName;
        this.url = url;
    }

    /**
     * {@return the title of the dataset or project}.
     * Examples: "EPSG", "PROJ4J".
     */
    @Override
    public InternationalString getTitle() {
        return LocalizedString.wrap(title);
    }

    /**
     * {@return a description of how the dataset or project is presented}.
     */
    @Override
    public Collection<PresentationForm> getPresentationForms() {
        return Collections.singleton(PresentationForm.valueOf("SOFTWARE"));
    }

    /**
     * {@return the organization together with other information such as the organization role}.
     * This is the method invoked by users for accessing {@link #getOrganisationName()}.
     */
    @Override
    public Collection<ResponsibleParty> getCitedResponsibleParties() {
        return Collections.singletonList(this);
    }

    /**
     * {@return the organization responsible for the maintenance of the dataset or project}.
     * Examples: "IOGP", "Eclipse".
     */
    @Override
    public InternationalString getOrganisationName() {
        return LocalizedString.wrap(organization);
    }

    /**
     * {@return the role of the organization regarding the software or data}.
     */
    @Override
    public Role getRole() {
        return Role.OWNER;
    }

    /**
     * {@return information for contacting the responsible party}.
     */
    @Override
    public Contact getContactInfo() {
        return this;
    }

    /**
     * {@return information about how to contact the organization}.
     * Note that this is a member of contact information, not project information.
     *
     * <p>Note: for providing a link to the project instead of the organization,
     * we need to wait for the release of GeoAPI 3.1.</p>
     */
    @Override
    public OnlineResource getOnlineResource() {
        return this;
    }

    /**
     * {@return name of the online resource}. It describes the content of {@link #getLinkage()},
     * which is about the organization, not the project.
     */
    @Override
    public String getName() {
        return urlName;
    }

    /**
     * {@return URL to the organization web site}.
     * Note that this is a member of contact information, not project information.
     */
    @Override
    public URI getLinkage() {
        return URI.create(url);
    }

    /**
     * {@return the purpose of the linkage}.
     */
    @Override
    public OnLineFunction getFunction() {
        return OnLineFunction.INFORMATION;
    }

    @Override
    public Collection<InternationalString> getAlternateTitles() {
        return Collections.emptyList();
    }

    @Override
    public InternationalString getCollectiveTitle() {
        return null;
    }

    @Override
    public Collection<CitationDate> getDates() {
        return Collections.emptyList();
    }

    @Override
    public Date getEditionDate() {
        return null;
    }

    @Override
    public InternationalString getEdition() {
        return null;
    }

    @Override
    public Series getSeries() {
        return null;
    }

    @Override
    public InternationalString getOtherCitationDetails() {
        return null;
    }

    @Override
    public String getISBN() {
        return null;
    }

    @Override
    public String getISSN() {
        return null;
    }

    @Override
    public String getIndividualName() {
        return null;
    }

    @Override
    public InternationalString getPositionName() {
        return null;
    }

    @Override
    public Telephone getPhone() {
        return null;
    }

    @Override
    public Address getAddress() {
        return null;
    }

    @Override
    public InternationalString getHoursOfService() {
        return null;
    }

    @Override
    public InternationalString getContactInstructions() {
        return null;
    }

    @Override
    public String getProtocol() {
        return null;
    }

    @Override
    public String getApplicationProfile() {
        return null;
    }

    @Override
    public InternationalString getDescription() {
        return null;
    }

    @Override
    public Collection<Identifier> getIdentifiers() {
        return Collections.emptySet();
    }
}
