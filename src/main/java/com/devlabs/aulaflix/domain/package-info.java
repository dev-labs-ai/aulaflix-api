/**
 * The codes the API and the database share: the values a request carries, a response shows and a column stores. The
 * entities and the DTOs both use them, so they sit outside {@code domain.entity}, which only the services and the
 * repositories may reach.
 */
package com.devlabs.aulaflix.domain;
