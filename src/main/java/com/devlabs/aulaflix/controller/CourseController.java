package com.devlabs.aulaflix.controller;

import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.dto.CourseDetail;
import com.devlabs.aulaflix.dto.CourseList;
import com.devlabs.aulaflix.exception.QueryParametersNotAllowedException;
import com.devlabs.aulaflix.service.CatalogService;

/** The public catalog, which needs no session and answers the same to everyone. */
@RestController
@RequestMapping("/v1/courses")
@Tag(name = "Catalog", description = "The Courses Visitors see: Coming soon and On sale, never a Draft")
public class CourseController {

    private final CatalogService catalog;

    public CourseController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @Operation(summary = "List the catalog", description = """
            Every Coming soon and On sale Course, unpaginated: On sale first, then the newest launch or announcement \
            first. The web filters by Area and state itself.""")
    @ApiResponse(responseCode = "200", description = "The catalog",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CourseList.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request`: the list takes no query parameters")
    public CourseList list(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new QueryParametersNotAllowedException();
        }
        return catalog.list();
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Read a Course's page", description = """
            What the list shows, plus the marketing copy and, while the Course is Coming soon, its Planned topics. A \
            Draft answers like a slug no Course has, so probing reveals no unannounced Course.""")
    @ApiResponse(responseCode = "200", description = "The Course",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CourseDetail.class)))
    @ApiResponse(responseCode = "404", description = "`course-not-found`: no Course has the slug, or it is a Draft")
    public CourseDetail detail(@PathVariable String slug) {
        return catalog.detail(slug);
    }
}
