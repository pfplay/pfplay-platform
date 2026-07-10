package com.pfplaybackend.api.administration.application.dto;

import java.util.List;

public record AttendanceAnalytics(long totalEntered, long totalExited,
                                  long uniqueVisitors, List<DailyAttendanceBucket> daily) {}
