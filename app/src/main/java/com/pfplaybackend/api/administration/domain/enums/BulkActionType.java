package com.pfplaybackend.api.administration.domain.enums;

/**
 * B-8 bulk action: 일괄 처리 가능한 admin partyroom action 종류.
 *
 * <p>PR 8 MVP scope (spec §2.2): TERMINATE, SUSPEND, SET_HIDDEN.
 * RESTORE / SET_FEATURED / SET_NORMAL / UPDATE_META 등 bulk는 차후 PR로 연기.
 */
public enum BulkActionType { TERMINATE, SUSPEND, SET_HIDDEN }
