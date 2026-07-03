package com.enterprise.ems.department.dto;

/**
 * Deliberately contains no headcount/employee-count field: that data
 * belongs to Employee Service's database, and computing it correctly
 * (live cross-service call vs. an eventually-consistent local cache)
 * is the subject of Steps 8-9, not this one. What's here is everything
 * this service can answer truthfully using only its own data today.
 */
public record DepartmentStatisticsResponse(
        Long departmentId,
        int directChildrenCount,
        int totalDescendantCount,
        int depthFromRoot
) {
}
