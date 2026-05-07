package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListFilter;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Cross-BC read repository for the admin partyroom list (B-1).
 *
 * The implementation joins Party and User BC tables in a single SQL query and
 * projects directly into {@link AdminPartyroomListRow}. This is the single
 * authorized cross-BC entity reference inside the Administration BC; ArchUnit
 * (Task 18) enforces that no other class under {@code administration} touches
 * Party/User entity types.
 */
public interface AdminPartyroomQueryRepository {

    Page<AdminPartyroomListRow> findAdminList(AdminPartyroomListFilter filter, Pageable pageable);
}
