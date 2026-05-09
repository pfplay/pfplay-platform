package com.pfplaybackend.api.user.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.value.Nickname;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserProfileRepository extends JpaRepository<ProfileData, Long> {
    List<ProfileData> findAllByUserIdIn(List<UserId> userIds);
    ProfileData findByUserId(UserId userId);

    @Query("SELECT COUNT(p) > 0 FROM ProfileData p WHERE p.bio.nickname = :nickname")
    boolean existsByNickname(@Param("nickname") Nickname nickname);

    @Query("SELECT COUNT(p) > 0 FROM ProfileData p WHERE p.bio.nickname = :nickname AND p.userId <> :userId")
    boolean existsByNicknameAndUserIdNot(@Param("nickname") Nickname nickname, @Param("userId") UserId userId);
}
