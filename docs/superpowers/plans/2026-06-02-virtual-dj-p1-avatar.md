# 가상 DJ(P1) 봇 아바타 변별 셋팅 콘솔 — 구현 플랜

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어드민이 봇(가상 DJ) 계정에 카탈로그의 다양한 아바타를 일괄(셋 랜덤 분배)·개별로 입혀 방을 시각적으로 살아있게 보이게 하고, 봇 생성 시점부터 null-icon 갭을 원천 제거한다.

**Architecture:** 합성 규칙(combinable→공통 face 합성 / standalone→자체 아이콘)을 `BotAvatarAssigner`(virtualdj 모듈) 단일 소스에 두고, 랜덤 배분·개별 적용·provision 자동부여가 모두 이 헬퍼를 거친다. 카탈로그는 `AvatarCatalogQueryUseCase`(avatar BC)에서 읽고, 봇 멤버 아바타 적용은 `BotAvatarApplyPort`(→ `AdminUserService.updateVirtualMemberAvatar`)로 나간다. 어드민 엔드포인트는 P2 `AdminVirtualDjController`에 합류(`canManageVirtualDj` 게이트). 어드민 프론트는 풀 페이지에 봇 로스터 + 아바타 피커 + 일괄/개별 다이얼로그를 P2 패턴 계승해 추가.

**Tech Stack:** Spring Boot(JDK 21, Gradle multi-module: `app`/`avatar`/`user`), QueryDSL, JUnit5 + Mockito + `@WebMvcTest` + IT(`@SpringBootTest`), ArchUnit. 프론트: React + TypeScript(Vite) + react-query + zod + vitest + MSW.

> **빌드 prefix 필수:** Gradle 호출 시 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` ([[reference_pfplay_platform_jdk]]).
> **마이그레이션 없음:** P1은 `user_profile` 기존 컬럼만 갱신 → 신규 Flyway 없음(부팅 drift 리스크 낮음, 단 e2e 게이트는 유지).
> **브랜치:** `feature/virtual-dj-p1-avatar`(이미 origin/develop 분기, spec 커밋됨). 백엔드 PR 먼저, 프론트 PR(pfplay-admin) 별도.
> **spec:** `docs/superpowers/specs/2026-06-02-virtual-dj-p1-avatar-design.md`

---

## Chunk 1: 백엔드 — 합성 규칙 코어 (`BotAvatarAssigner` + 포트)

> 모듈 `app`, 패키지 `com.pfplaybackend.api.virtualdj`. 이 chunk는 순수 로직 + 시임만; 엔드포인트/쿼리는 Chunk 2.
> 합성 규칙 근거(spec §1.2/§1.3): combinable 바디(`is_combinable=1`, `icon_uri` NULL)는 face 없이는 아이콘이 NULL이 된다 → **반드시 기본 face 합성**. standalone 바디(`icon_uri` 보유)는 face="" → 자체 아이콘.

### Task 1.1: `Randomizer` 포트 + 어댑터 (테스트 결정성)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/port/Randomizer.java`
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/adapter/out/random/ThreadLocalRandomizer.java`

- [ ] **Step 1: 포트 인터페이스 작성**

```java
package com.pfplaybackend.api.virtualdj.application.port;

/**
 * 셋→봇 랜덤 배분의 인덱스 선택을 추상화한다. 운영은 ThreadLocalRandom,
 * 테스트는 결정적 stub 을 주입해 분배 결과를 검증 가능하게 한다.
 */
public interface Randomizer {
    /** {@code [0, bound)} 범위의 인덱스를 반환한다. {@code bound <= 0} 이면 IllegalArgumentException. */
    int nextIndex(int bound);
}
```

- [ ] **Step 2: 어댑터 작성**

```java
package com.pfplaybackend.api.virtualdj.adapter.out.random;

import com.pfplaybackend.api.virtualdj.application.port.Randomizer;
import org.springframework.stereotype.Component;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class ThreadLocalRandomizer implements Randomizer {
    @Override
    public int nextIndex(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be > 0, got " + bound);
        return ThreadLocalRandom.current().nextInt(bound);
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/virtualdj/application/port/Randomizer.java \
        app/src/main/java/com/pfplaybackend/api/virtualdj/adapter/out/random/ThreadLocalRandomizer.java
git commit -m "feat(가상DJ P1): Randomizer 포트+ThreadLocalRandom 어댑터 (#288)"
```

### Task 1.2: `BotAvatarApplyPort` + 어댑터 (admin 시임)

> `VirtualMemberProvisionPort`/`VirtualMemberProvisionAdapter` 패턴 미러. virtualdj 중 `..admin..` import 허용은 `adapter.out.provision` 패키지뿐(ArchUnit 호환).

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/port/BotAvatarApplyPort.java`
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/adapter/out/provision/BotAvatarApplyAdapter.java`

- [ ] **Step 1: 포트 작성**

```java
package com.pfplaybackend.api.virtualdj.application.port;

import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.common.domain.value.UserId;

/**
 * virtualdj 가 봇 멤버 아바타를 갱신하기 위해 admin BC 로 나가는 outbound port.
 * 구현체(provision 어댑터)만 admin 레이어를 만진다 — 합성/아이콘 결정은 admin 쪽 기존 로직 재사용.
 */
public interface BotAvatarApplyPort {
    /** 봇의 아바타를 (body, face) 로 갱신한다. face 빈값이면 SINGLE_BODY, 있으면 BODY_WITH_FACE 로 admin 이 합성/아이콘 결정. */
    void apply(UserId botUserId, AvatarBodyUri bodyUri, AvatarFaceUri faceUri);
}
```

- [ ] **Step 2: 어댑터 작성** (기존 `AdminUserService.updateVirtualMemberAvatar(UserId, AvatarBodyUri, AvatarFaceUri)` 위임)

```java
package com.pfplaybackend.api.virtualdj.adapter.out.provision;

import com.pfplaybackend.api.admin.application.service.AdminUserService;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.virtualdj.application.port.BotAvatarApplyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BotAvatarApplyAdapter implements BotAvatarApplyPort {

    private final AdminUserService adminUserService;

    @Override
    public void apply(UserId botUserId, AvatarBodyUri bodyUri, AvatarFaceUri faceUri) {
        adminUserService.updateVirtualMemberAvatar(botUserId, bodyUri, faceUri);
    }
}
```

- [ ] **Step 3: 컴파일 확인** — `:app:compileJava -q` → SUCCESSFUL
- [ ] **Step 4: 커밋** — `feat(가상DJ P1): BotAvatarApplyPort + admin 시임 어댑터 (#288)`

### Task 1.3: `BotAvatarAssigner` 합성 규칙 — 실패 테스트 먼저

> 합성 규칙은 순수 로직: 주어진 bodyUri를 카탈로그에서 조회 → combinable 이면 기본 face 합성(non-empty faceUri), 아니면 face="". 카탈로그/적용/랜덤은 mock. **icon non-null 종단 단언은 Chunk 2/3 IT 책임**(여기선 "combinable→face 전달" 규칙만).

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/virtualdj/BotAvatarAssignerTest.java`
- (다음 Task에서) Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/BotAvatarAssigner.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.virtualdj.application.port.BotAvatarApplyPort;
import com.pfplaybackend.api.virtualdj.application.port.Randomizer;
import com.pfplaybackend.api.virtualdj.application.service.BotAvatarAssigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BotAvatarAssignerTest {

    private AvatarCatalogQueryUseCase catalog;
    private BotAvatarApplyPort applyPort;
    private Randomizer randomizer;
    private BotAvatarAssigner assigner;

    private static final String FACE_URI = "https://cdn/ava_face_basic_001.png";
    private static final String COMBINABLE_BODY = "https://cdn/ava_body_basic_001.png";
    private static final String STANDALONE_BODY = "https://cdn/ava_body_basic_002.png";

    @BeforeEach
    void setUp() {
        catalog = mock(AvatarCatalogQueryUseCase.class);
        applyPort = mock(BotAvatarApplyPort.class);
        randomizer = mock(Randomizer.class);
        assigner = new BotAvatarAssigner(catalog, applyPort, randomizer);

        // 기본 face = 단일 basic face
        given(catalog.findPublishedFaces())
                .willReturn(List.of(face(FACE_URI)));
        given(catalog.findBodyByUri(COMBINABLE_BODY)).willReturn(Optional.of(body(COMBINABLE_BODY, true)));
        given(catalog.findBodyByUri(STANDALONE_BODY)).willReturn(Optional.of(body(STANDALONE_BODY, false)));
    }

    @Test
    void combinable_바디는_기본_face_를_합성해_적용한다() {
        assigner.assignOne(new UserId(1L), COMBINABLE_BODY);

        ArgumentCaptor<AvatarFaceUri> faceCap = ArgumentCaptor.forClass(AvatarFaceUri.class);
        verify(applyPort).apply(eq(new UserId(1L)), eq(new AvatarBodyUri(COMBINABLE_BODY)), faceCap.capture());
        assertThat(faceCap.getValue().getValue()).isEqualTo(FACE_URI);  // non-empty = BODY_WITH_FACE
    }

    @Test
    void standalone_바디는_빈_face_로_적용한다() {
        assigner.assignOne(new UserId(2L), STANDALONE_BODY);

        ArgumentCaptor<AvatarFaceUri> faceCap = ArgumentCaptor.forClass(AvatarFaceUri.class);
        verify(applyPort).apply(eq(new UserId(2L)), eq(new AvatarBodyUri(STANDALONE_BODY)), faceCap.capture());
        assertThat(faceCap.getValue().getValue()).isEmpty();  // empty = SINGLE_BODY
    }

    @Test
    void distribute_는_셋에서_Randomizer_인덱스로_봇별_배분한다() {
        // 봇 2명, 셋 [standalone, combinable]; randomizer 가 0,1 반환 → 각각 배분
        given(randomizer.nextIndex(2)).willReturn(0, 1);

        var assigned = assigner.distribute(
                List.of(new UserId(1L), new UserId(2L)),
                List.of(STANDALONE_BODY, COMBINABLE_BODY));

        assertThat(assigned).hasSize(2);
        verify(applyPort).apply(eq(new UserId(1L)), eq(new AvatarBodyUri(STANDALONE_BODY)), any());
        verify(applyPort).apply(eq(new UserId(2L)), eq(new AvatarBodyUri(COMBINABLE_BODY)), any());
    }

    @Test
    void distribute_빈_셋이면_INVALID() {
        assertThatThrownBy(() -> assigner.distribute(List.of(new UserId(1L)), List.of()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void distribute_빈_봇목록이면_INVALID() {
        assertThatThrownBy(() -> assigner.distribute(List.of(), List.of(STANDALONE_BODY)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void 카탈로그에_없는_bodyUri_는_INVALID() {
        given(catalog.findBodyByUri("https://cdn/unknown.png")).willReturn(Optional.empty());
        assertThatThrownBy(() -> assigner.assignOne(new UserId(1L), "https://cdn/unknown.png"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void assignRandomFromCatalog_는_published_바디에서_랜덤_1개를_적용한다() {
        given(catalog.findPublishedBodies())
                .willReturn(List.of(body(STANDALONE_BODY, false), body(COMBINABLE_BODY, true)));
        given(randomizer.nextIndex(2)).willReturn(1);

        assigner.assignRandomFromCatalog(new UserId(9L));

        verify(applyPort).apply(eq(new UserId(9L)), eq(new AvatarBodyUri(COMBINABLE_BODY)), any());
    }

    private static AvatarBodyDto body(String uri, boolean combinable) {
        return AvatarBodyDto.builder().name("b").resourceUri(uri).iconUri(combinable ? null : uri + ".icon")
                .combinable(combinable).build();
    }

    private static AvatarFaceDto face(String uri) {
        // AvatarFaceDto = 4-arg record (long id, String name, String resourceUri, boolean available)
        return new AvatarFaceDto(1L, "ava_face_basic_001", uri, true);
    }
}
```

> **NOTE (executor):** `AvatarFaceDto`는 4-component record `(long id, String name, String resourceUri, boolean available)` (검증됨). `.resourceUri()` 접근자로 읽는다. 목적은 `resourceUri==FACE_URI` 인 face 1건 반환.

- [ ] **Step 2: 컴파일/실행 → 실패 확인** (`BotAvatarAssigner` 미존재)

Run: `JAVA_HOME=... ./gradlew :app:test --tests "*BotAvatarAssignerTest" -q`
Expected: 컴파일 에러(심볼 없음) 또는 FAIL

### Task 1.4: `BotAvatarAssigner` 구현 → 테스트 GREEN

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/BotAvatarAssigner.java`

- [ ] **Step 1: 구현 작성**

```java
package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.virtualdj.application.port.BotAvatarApplyPort;
import com.pfplaybackend.api.virtualdj.application.port.Randomizer;
import com.pfplaybackend.api.virtualdj.domain.exception.VirtualDjException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 봇 아바타 변별 배분의 단일 소스 — 합성 규칙(combinable→공통 face / standalone→자체 아이콘)을
 * 여기 한 곳에만 두고, 개별 적용 / 셋 랜덤 배분 / provision 자동부여가 모두 이 헬퍼를 거친다.
 *
 * <p>합성 규칙(spec §1.2/§1.3): combinable 바디는 icon_uri 가 NULL 이라 face 없이는 아이콘이 깨진다.
 * 따라서 기본 basic face 를 합성해 BODY_WITH_FACE(face 페어 아이콘)로 만들고, standalone 바디는
 * face="" 로 SINGLE_BODY(자체 아이콘)로 둔다. 합성/아이콘의 실제 결정은 admin 쪽 update 로직이 수행.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotAvatarAssigner {

    private final AvatarCatalogQueryUseCase catalog;
    private final BotAvatarApplyPort applyPort;
    private final Randomizer randomizer;

    public record Assigned(Long userId, String avatarBodyUri) {}

    /** 전체 published 카탈로그에서 랜덤 1개를 골라 적용(provision 자동부여 + 단발 변별용). */
    public void assignRandomFromCatalog(UserId botUserId) {
        List<AvatarBodyDto> bodies = catalog.findPublishedBodies();
        if (bodies.isEmpty()) {
            throw ExceptionCreator.create(VirtualDjException.INVALID_AVATAR_SET);
        }
        String bodyUri = bodies.get(randomizer.nextIndex(bodies.size())).getResourceUri();
        assignOne(botUserId, bodyUri);
    }

    /** 단일 봇에 지정 바디 적용(개별 셋팅). 합성 규칙 적용. */
    public void assignOne(UserId botUserId, String bodyUri) {
        AvatarBodyDto body = catalog.findBodyByUri(bodyUri)
                .orElseThrow(() -> ExceptionCreator.create(VirtualDjException.INVALID_AVATAR_SET));
        AvatarFaceUri faceUri = body.isCombinable()
                ? new AvatarFaceUri(defaultFaceUri())
                : new AvatarFaceUri();   // 빈 face = SINGLE_BODY
        applyPort.apply(botUserId, new AvatarBodyUri(bodyUri), faceUri);
    }

    /** 셋({@code bodyUris})에서 봇별 랜덤 1개를 배분(일괄). */
    public List<Assigned> distribute(List<UserId> botIds, List<String> bodyUris) {
        if (bodyUris == null || bodyUris.isEmpty() || botIds == null || botIds.isEmpty()) {
            throw ExceptionCreator.create(VirtualDjException.INVALID_AVATAR_SET);
        }
        // 셋 무결성: 모든 bodyUri 가 카탈로그에 존재해야 한다.
        for (String uri : bodyUris) {
            if (catalog.findBodyByUri(uri).isEmpty()) {
                throw ExceptionCreator.create(VirtualDjException.INVALID_AVATAR_SET);
            }
        }
        List<Assigned> assigned = new ArrayList<>();
        for (UserId botId : botIds) {
            String chosen = bodyUris.get(randomizer.nextIndex(bodyUris.size()));
            assignOne(botId, chosen);
            assigned.add(new Assigned(botId.getUid(), chosen));
        }
        log.info("[BotAvatarAssigner.distribute] bots={} setSize={}", botIds.size(), bodyUris.size());
        return assigned;
    }

    private String defaultFaceUri() {
        List<AvatarFaceDto> faces = catalog.findPublishedFaces();
        if (faces.isEmpty()) {
            throw ExceptionCreator.create(VirtualDjException.INVALID_AVATAR_SET);
        }
        return faces.get(0).resourceUri();   // 단일 basic face (AvatarFaceDto 접근자에 맞춰 조정)
    }
}
```

> **NOTE (executor):** (1) **`VirtualDjException`에 신규 상수 `INVALID_AVATAR_SET`(코드 예: `VDJ-008`, `ErrorType.BAD_REQUEST`=400) 추가** 후 사용 — 기존 `INVALID_CONFIG` 메시지는 "MANAGED 전환에 targetCount/floor 필요"로 빈 셋/미존재 URI 에는 오해를 준다([[feedback_elegant_no_code_dirtying]]). 기존 enum 패턴(코드/메시지/ErrorType) 그대로 따른다. (2) `AvatarFaceDto.resourceUri()` = record accessor(검증됨). (3) `UserId.getUid()` = Long, `new UserId(Long)` 생성자 존재(검증됨).

- [ ] **Step 2: 테스트 GREEN 확인**

Run: `JAVA_HOME=... ./gradlew :app:test --tests "*BotAvatarAssignerTest" -q`
Expected: PASS (7 tests)

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/BotAvatarAssigner.java \
        app/src/test/java/com/pfplaybackend/api/virtualdj/BotAvatarAssignerTest.java
git commit -m "feat(가상DJ P1): BotAvatarAssigner 합성 규칙 단일 소스 (#288)"
```

---

## Chunk 2: 백엔드 — 로스터 쿼리 + 카탈로그 + 엔드포인트

### Task 2.1: 봇 로스터 쿼리 (`findRoster`) + IT

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/dto/BotRosterRow.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/virtualdj/adapter/out/persistence/BotPoolQueryRepository.java` (메서드 추가)
- Modify: `app/src/main/java/com/pfplaybackend/api/virtualdj/adapter/out/persistence/impl/BotPoolQueryRepositoryImpl.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/virtualdj/BotPoolQueryRepositoryImplIT.java`

- [ ] **Step 1: DTO 작성**

```java
package com.pfplaybackend.api.virtualdj.application.dto;

/** 봇 로스터 1행 — 봇 신원 + 현재 아바타 + 배치 룸(없으면 null). */
public record BotRosterRow(
        Long userId,
        String nickname,
        String avatarBodyUri,
        String avatarIconUri,
        Long placementPartyroomId,
        String placementPartyroomTitle
) {}
```

- [ ] **Step 2: 인터페이스에 메서드 추가**

```java
    /**
     * 봇 전체 로스터(is_dummy=true, withdrawn_at IS NULL) — 닉네임/현재 아바타 + 현재 배치된
     * ACTIVE 파티룸(활성 crew 기준, 없으면 null). uid 오름차순(oldest-first).
     */
    List<BotRosterRow> findRoster();
```
(import `com.pfplaybackend.api.virtualdj.application.dto.BotRosterRow;` 추가)

- [ ] **Step 3: impl 작성** (profile 조인 + crew→partyroom left join)

```java
    @Override
    public List<BotRosterRow> findRoster() {
        List<Tuple> tuples = queryFactory
                .select(
                        userAccountData.userId.uid,
                        profileData.bio.nickname,   // @Convert(Nickname) → tuple.get 시 Nickname 객체 반환
                        profileData.avatarSetting.avatarBodyUri.value,
                        profileData.avatarSetting.avatarIconUri.value,
                        partyroomData.id,
                        partyroomData.title)
                .from(userAccountData)
                .join(profileData).on(profileData.userId.uid.eq(userAccountData.userId.uid))
                .leftJoin(crewData).on(crewData.userId.uid.eq(userAccountData.userId.uid)
                        .and(crewData.isActive.isTrue()))
                .leftJoin(partyroomData).on(partyroomData.id.eq(crewData.partyroomId.id)
                        .and(partyroomData.status.eq(PartyroomStatus.ACTIVE)))
                .where(
                        userAccountData.isDummy.isTrue(),
                        userAccountData.withdrawnAt.isNull())
                .orderBy(userAccountData.userId.uid.asc())
                .fetch();

        return tuples.stream()
                .map(t -> {
                    Nickname nick = t.get(profileData.bio.nickname);
                    return new BotRosterRow(
                        t.get(userAccountData.userId.uid),
                        nick == null ? null : nick.value(),
                        t.get(profileData.avatarSetting.avatarBodyUri.value),
                        t.get(profileData.avatarSetting.avatarIconUri.value),
                        t.get(partyroomData.id),
                        t.get(partyroomData.title));
                })
                .toList();
    }
```

> **검증된 Q-경로 (executor):** `QProfileData.profileData` (import `static ...QProfileData.profileData`). 아바타는 `@Embeddable`+`@Column value` 중첩이라 `avatarSetting.avatarBodyUri.value` / `...avatarIconUri.value` 가 맞다(검증됨). **nickname 은 `bio.nickname`** — `Bio.nickname` 이 `@Convert(NicknameConverter)` 단일 컬럼이라 `profileData.bio.nickname` 은 `SimplePath<Nickname>` 이고 tuple.get 시 `Nickname` 객체를 돌려준다. `.value()` (record accessor) 로 String 추출. import `com.pfplaybackend.api.user.domain.value.Nickname`. 봇은 한 번에 1방만 활성 crew(invariant)라 left join row 중복 없음.

- [ ] **Step 4: IT 추가** (`BotPoolQueryRepositoryImplIT`에 테스트 메서드 추가)

```java
    @Test
    void findRoster_는_봇의_닉네임_아바타_배치룸을_반환한다() {
        // given: 봇 2명 시드(기존 헬퍼 재사용), 1명은 ACTIVE 방에 활성 crew 배치, 1명은 idle
        // when
        List<BotRosterRow> roster = repository.findRoster();
        // then: 2행, idle 봇은 placementPartyroomId == null, 배치 봇은 != null,
        //       avatarBodyUri/nickname non-null
        assertThat(roster).hasSize(2);
        assertThat(roster).allSatisfy(r -> {
            assertThat(r.nickname()).isNotBlank();
            assertThat(r.avatarBodyUri()).isNotNull();
        });
    }
```

> **NOTE (executor):** `BotPoolQueryRepositoryImplIT`의 기존 봇/crew/partyroom 시드 헬퍼를 재사용. 비-봇(실유저)이 로스터에 안 섞이는지도 1건 단언 추가.

- [ ] **Step 5: IT 실행** — `./gradlew :app:integrationTest --tests "*BotPoolQueryRepositoryImplIT" -q` → PASS
- [ ] **Step 6: 커밋** — `feat(가상DJ P1): 봇 로스터 쿼리 findRoster (#288)`

### Task 2.2: `BotAvatarAdminService` (로스터/카탈로그/개별/일괄)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/BotAvatarAdminService.java`
- Create: `app/src/test/java/com/pfplaybackend/api/virtualdj/BotAvatarAdminServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성** (mock 기반 위임 검증)

```java
package com.pfplaybackend.api.virtualdj;
// ... imports: mockito, AvatarCatalogQueryUseCase, BotPoolQueryRepository, BotAvatarAssigner, BotAvatarAdminService

class BotAvatarAdminServiceTest {
    // catalog() → findPublishedBodies 매핑(bodyUri/name/thumbnailUri/combinable/obtainableType)
    // roster() → repository.findRoster 위임
    // setIndividual(id, uri) → assigner.assignOne 위임
    // distribute(ids, uris) → assigner.distribute 위임 + 결과 반환
}
```

- [ ] **Step 2: 구현 작성**

```java
package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogQueryUseCase;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.application.dto.BotRosterRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 봇 아바타 어드민 운영 — 카탈로그 조회 / 로스터 조회 / 개별·일괄 변별 배분. */
@Service
@RequiredArgsConstructor
public class BotAvatarAdminService {

    private final BotPoolQueryRepository botPoolQueryRepository;
    private final AvatarCatalogQueryUseCase catalog;
    private final BotAvatarAssigner assigner;

    public record CatalogItem(String bodyUri, String name, String thumbnailUri,
                              boolean combinable, String obtainableType) {}

    @Transactional(readOnly = true)
    public List<CatalogItem> catalog() {
        return catalog.findPublishedBodies().stream()
                .map(BotAvatarAdminService::toCatalogItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BotRosterRow> roster() {
        return botPoolQueryRepository.findRoster();
    }

    @Transactional
    public void setIndividual(UserId botUserId, String bodyUri) {
        assigner.assignOne(botUserId, bodyUri);
    }

    @Transactional
    public List<BotAvatarAssigner.Assigned> distribute(List<Long> botIds, List<String> bodyUris) {
        List<UserId> ids = botIds.stream().map(UserId::new).toList();
        return assigner.distribute(ids, bodyUris);
    }

    private static CatalogItem toCatalogItem(AvatarBodyDto b) {
        return new CatalogItem(b.getResourceUri(), b.getName(), b.getResourceUri(),
                b.isCombinable(), b.getObtainableType() == null ? null : b.getObtainableType().name());
    }
}
```

> **NOTE (executor):** distribute 가 비-봇 userId 를 받으면 — `assignOne`→`updateVirtualMemberAvatar` 가 비-봇/미존재에 대해 던지는 예외를 확인. spec §2.3 "비-봇 무시(부분성공)" 의미를 살리려면 distribute 루프에서 per-bot try/catch 로 격리하고 성공분만 `assigned`에 담는 방식으로 보강(또는 단순화: 미존재 봇은 404로 묶지 말고 무시). 결정은 executor 가 `updateVirtualMemberAvatar` 의 미존재 동작 확인 후 택1, 테스트로 잠금.

- [ ] **Step 3: 테스트 GREEN** — `./gradlew :app:test --tests "*BotAvatarAdminServiceTest" -q` → PASS
- [ ] **Step 4: 커밋** — `feat(가상DJ P1): BotAvatarAdminService (카탈로그/로스터/배분) (#288)`

### Task 2.3: 페이로드 + 컨트롤러 엔드포인트 4종

**Files:**
- Create: `.../adapter/in/web/payload/AvatarCatalogItemResponse.java`
- Create: `.../adapter/in/web/payload/BotRosterItemResponse.java`
- Create: `.../adapter/in/web/payload/SetBotAvatarRequest.java`
- Create: `.../adapter/in/web/payload/DistributeBotAvatarRequest.java`
- Create: `.../adapter/in/web/payload/DistributeBotAvatarResponse.java`
- Modify: `.../adapter/in/web/AdminVirtualDjController.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/virtualdj/AdminVirtualDjControllerTest.java`

- [ ] **Step 1: 페이로드 작성** (P2 payload 스타일: record + static `from`)

```java
// AvatarCatalogItemResponse
public record AvatarCatalogItemResponse(String bodyUri, String name, String thumbnailUri,
                                        boolean combinable, String obtainableType) {
    public static AvatarCatalogItemResponse from(BotAvatarAdminService.CatalogItem c) {
        return new AvatarCatalogItemResponse(c.bodyUri(), c.name(), c.thumbnailUri(), c.combinable(), c.obtainableType());
    }
}
// BotRosterItemResponse
public record BotRosterItemResponse(Long userId, String nickname, String avatarBodyUri, String avatarIconUri,
                                    Long placementRoomId, String placementRoomTitle) {
    public static BotRosterItemResponse from(BotRosterRow r) {
        return new BotRosterItemResponse(r.userId(), r.nickname(), r.avatarBodyUri(), r.avatarIconUri(),
                r.placementPartyroomId(), r.placementPartyroomTitle());
    }
}
// SetBotAvatarRequest
public record SetBotAvatarRequest(@jakarta.validation.constraints.NotBlank String avatarBodyUri) {}
// DistributeBotAvatarRequest
public record DistributeBotAvatarRequest(
        @jakarta.validation.constraints.NotEmpty List<Long> botIds,
        @jakarta.validation.constraints.NotEmpty List<String> bodyUris) {}
// DistributeBotAvatarResponse
public record DistributeBotAvatarResponse(List<Assigned> assigned) {
    public record Assigned(Long userId, String avatarBodyUri) {}
    public static DistributeBotAvatarResponse from(List<BotAvatarAssigner.Assigned> xs) {
        return new DistributeBotAvatarResponse(xs.stream()
                .map(a -> new Assigned(a.userId(), a.avatarBodyUri())).toList());
    }
}
```

- [ ] **Step 2: 컨트롤러에 4 엔드포인트 추가** (AdminVirtualDjController, `BotAvatarAdminService` 주입 추가)

```java
    private final BotAvatarAdminService botAvatarAdminService;

    // ── 봇 아바타 (P1) ──

    @Operation(summary = "아바타 카탈로그 조회 (피커용)")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/virtual-dj/avatar-catalog")
    public ResponseEntity<ApiCommonResponse<List<AvatarCatalogItemResponse>>> avatarCatalog() {
        List<AvatarCatalogItemResponse> items = botAvatarAdminService.catalog().stream()
                .map(AvatarCatalogItemResponse::from).toList();
        return ResponseEntity.ok(ApiCommonResponse.success(items));
    }

    @Operation(summary = "봇 로스터 조회 (신원+현재 아바타+배치룸)")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @GetMapping("/virtual-dj/bots")
    public ResponseEntity<ApiCommonResponse<List<BotRosterItemResponse>>> bots() {
        List<BotRosterItemResponse> items = botAvatarAdminService.roster().stream()
                .map(BotRosterItemResponse::from).toList();
        return ResponseEntity.ok(ApiCommonResponse.success(items));
    }

    @Operation(summary = "봇 개별 아바타 설정")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PutMapping("/virtual-dj/bots/{userId}/avatar")
    public ResponseEntity<Void> setBotAvatar(@PathVariable("userId") Long userId,
                                             @Valid @RequestBody SetBotAvatarRequest req) {
        botAvatarAdminService.setIndividual(new UserId(userId), req.avatarBodyUri());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "봇 아바타 일괄 변별 배분", description = "선택 봇들에 셋에서 랜덤 1개씩 배분")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("@adminAuth.canManageVirtualDj()")
    @PostMapping("/virtual-dj/bots/avatar/distribute")
    public ResponseEntity<ApiCommonResponse<DistributeBotAvatarResponse>> distributeBotAvatars(
            @Valid @RequestBody DistributeBotAvatarRequest req) {
        var assigned = botAvatarAdminService.distribute(req.botIds(), req.bodyUris());
        return ResponseEntity.ok(ApiCommonResponse.success(DistributeBotAvatarResponse.from(assigned)));
    }
```
(import: `UserId`, 신규 payload들, `BotAvatarAdminService`)

> **NOTE (executor):** `userId` path는 `AdminUserController`가 `UserId.fromString(String)`을 쓴다. 봇 userId가 Long 인지 UUID-문자열인지 확인 — `UserId`의 실제 표현(snowflake Long 으로 보임, `userId.uid` Long)에 맞춰 `@PathVariable Long` 또는 `String`+`UserId.fromString` 택1. 로스터가 Long userId 를 반환하므로 Long 일관 가정.

- [ ] **Step 3: 컨트롤러 슬라이스 테스트 추가** (`AdminVirtualDjControllerTest`, `BotAvatarAdminService` @MockBean 추가)

```java
    @Test @WithMockUser(roles = "ADMIN")
    void avatarCatalog_ADMIN_200() throws Exception { /* given service returns 1 item; GET → 200 jsonPath */ }

    @Test @WithMockUser(roles = "ADMIN")
    void bots_ADMIN_200() throws Exception { /* GET /virtual-dj/bots → 200 */ }

    @Test @WithMockUser(roles = "ADMIN")
    void setBotAvatar_ADMIN_204() throws Exception { /* PUT with body → 204, verify service */ }

    @Test @WithMockUser(roles = "ADMIN")
    void distribute_빈_botIds_400() throws Exception { /* POST {botIds:[],bodyUris:["x"]} → 400 (validation) */ }

    @Test @WithMockUser(roles = "MEMBER")
    void distribute_비어드민_403() throws Exception { /* → 403 */ }

    @Test @WithAnonymousUser
    void bots_익명_401() throws Exception { /* → 401 */ }

    @Test @WithMockUser(roles = "ADMIN")
    void distribute_CSRF_누락_403() throws Exception { /* csrf() 없이 POST → 403 */ }
```

> **NOTE (executor):** 기존 테스트의 `@MockBean` 목록과 `csrf()` post-processor 사용 패턴을 그대로 따른다. `BotAvatarAdminService`를 `@MockBean`으로 추가.

- [ ] **Step 4: 전체 슬라이스 테스트 GREEN** — `./gradlew :app:test --tests "*AdminVirtualDjControllerTest" -q` → PASS
- [ ] **Step 5: 커밋** — `feat(가상DJ P1): 봇 아바타 어드민 엔드포인트 4종 (카탈로그/로스터/개별/일괄) (#288)`

---

## Chunk 3: 백엔드 — provision 자동부여 + 전체 GREEN

### Task 3.1: provision 시 랜덤 변별 자동부여

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/VirtualUserPoolService.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/virtualdj/VirtualUserPoolServiceIT.java`

- [ ] **Step 1: IT 실패 테스트 추가** (provision 후 전원 icon non-null)

```java
    @Test
    void provision_후_모든_봇은_유효한_채팅_아이콘을_가진다() {
        // when
        List<UserId> bots = poolService.provision(5);
        // then: 각 봇의 user_profile.avatar_icon_uri 가 non-null·non-blank (null-icon 갭 제거)
        for (UserId id : bots) {
            ProfileData p = profileRepository.findByUserId(id).orElseThrow(); // 실제 조회 경로에 맞춰 조정
            assertThat(p.getAvatarSetting().getAvatarIconUriValue()).isNotBlank();
        }
    }
```

> **NOTE (executor):** 봇 프로필 조회 경로(`MemberRepository`/`ProfileRepository`/`AdminUserService.getVirtualMember`)는 IT 컨텍스트에 이미 있는 빈을 재사용. 핵심 단언 = `getAvatarIconUriValue()` non-blank. 이게 spec §0/§4-3 의 핵심 회귀 가드(combinable basic_001 디폴트의 null-icon 깨짐이 사라졌는지).

- [ ] **Step 2: 실패 확인** — 현재 provision 은 basic_001(combinable)+빈face → icon NULL → FAIL 예상

Run: `./gradlew :app:integrationTest --tests "*VirtualUserPoolServiceIT" -q`
Expected: 신규 테스트 FAIL (icon blank)

- [ ] **Step 3: provision 수정** (BotAvatarAssigner 주입 + 봇별 호출)

```java
    private final BotAvatarAssigner botAvatarAssigner;   // 생성자 주입 추가

    @Transactional
    public List<UserId> provision(int n) {
        List<UserId> created = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            MemberData member = virtualMemberProvisionPort.createVirtualMember(generateBotNickname());
            UserId botUserId = new UserId(member.getUserAccountId());

            UserAccountData account = userAccountRepository.findById(botUserId).orElseThrow();
            account.markAsDummy();

            playlistRepository.save(
                    PlaylistData.create(1, BOT_PLAYLIST_NAME, PlaylistType.PLAYLIST, botUserId));

            // P1: 생성 즉시 카탈로그 랜덤 변별 아바타 부여 (기존 깨진 basic_001 디폴트 대체).
            botAvatarAssigner.assignRandomFromCatalog(botUserId);

            created.add(botUserId);
        }
        return created;
    }
```

> **NOTE (executor):** 같은 `@Transactional` 안에서 `assignRandomFromCatalog`→`updateVirtualMemberAvatar`가 같은 영속 컨텍스트에 join 되는지(spec §6-C4). updateVirtualMemberAvatar 가 self-flush/이벤트 발행으로 부수효과 내면 IT 로 노출됨 — 그 경우 createVirtualMember 직후 같은 멤버를 다시 로드해 적용하거나, 순서를 markAsDummy 전/후로 조정. IT GREEN 이 기준.

- [ ] **Step 4: IT GREEN** — `./gradlew :app:integrationTest --tests "*VirtualUserPoolServiceIT" -q` → PASS
- [ ] **Step 5: 커밋** — `feat(가상DJ P1): provision 시 랜덤 변별 아바타 자동부여 — null-icon 갭 제거 (#288)`

### Task 3.2: ArchUnit + 전체 백엔드 GREEN

**Files:**
- Check: `app/src/test/java/com/pfplaybackend/api/virtualdj/VirtualDjArchitectureTest.java` (있으면)

- [ ] **Step 1: ArchUnit 통과 확인** — 신규 코드가 path A 규칙 위반 없는지(virtualdj→admin 직접 import 는 `adapter.out.provision`만). `BotAvatarApplyAdapter`가 그 패키지에 있으므로 OK. `BotAvatarAssigner`는 admin import 없음(catalog=avatar, apply=port). 위반 시 규칙 범위 재확인.

Run: `./gradlew :app:test --tests "*VirtualDjArchitectureTest" -q`
Expected: PASS

- [ ] **Step 2: 전체 백엔드 테스트 GREEN**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest :user:test -q`
Expected: BUILD SUCCESSFUL (전부 GREEN)

- [ ] **Step 3: 커밋(있으면 사소 보정)** — `test(가상DJ P1): ArchUnit + 전체 GREEN 확인 (#288)`

> **백엔드 PR 게이트:** 여기까지 GREEN 이면 PR(base develop) 가능. 단 **dev 머지는 Chunk 6(로컬 e2e) 통과 후**. 프론트(Chunk 4–5)는 백엔드 develop 머지 전이라면 MSW 로 독립 진행 가능.

---

## Chunk 4: 프론트(pfplay-admin) — 타입 + API + 카탈로그/로스터 + 피커

> 레포 `pfplay-admin`. 기존 `features/virtual-dj-pool` 확장. 패턴 출처: `features/virtual-dj-song-packs`(api/hook), `features/music-search`(boundary 매퍼), `features/partyrooms`(bulk 다이얼로그/체크박스). 테스트 vitest+MSW.
> **NOTE (executor):** 정확한 파일 경로/엔트리는 `pfplay-admin` 탐색 후 확정(spec §1.6). 아래는 의도와 계약.

### Task 4.1: 엔티티 타입 + API 클라이언트

**Files:**
- Modify: `src/entities/virtual-dj/model/types.ts` (타입 추가)
- Create: `src/features/virtual-dj-pool/api/avatar-catalog-api.ts` + `use-avatar-catalog.ts`
- Create: `src/features/virtual-dj-pool/api/bots-api.ts` + `use-bots.ts` / `use-set-bot-avatar.ts` / `use-distribute-avatars.ts`

- [ ] **Step 1: 타입 추가**

```ts
export interface AvatarCatalogItem {
  bodyUri: string; name: string; thumbnailUri: string; combinable: boolean; obtainableType: string | null;
}
export interface BotRosterItem {
  userId: number; nickname: string; avatarBodyUri: string; avatarIconUri: string;
  placementRoomId: number | null; placementRoomTitle: string | null;
}
```

> **NOTE (executor):** `src/entities/virtual-dj/index.ts` barrel 이 각 타입을 명시 re-export 한다(형제 api 파일이 `@/entities/virtual-dj` 에서 import). **새 두 타입을 barrel 에도 추가**해야 import 가 깨지지 않는다.

- [ ] **Step 2: API 함수** (기존 `song-packs-api.ts` 헤더 그대로 미러 — `http<ApiCommonResponse<T>>` 2단계 + `unwrap`)

> **검증된 시그니처:** `http<T = unknown>(path, opts?): Promise<T>` 는 파싱된 바디를 직접 반환(204면 undefined), body 자동 JSON.stringify + CSRF echo 자동. `unwrap<T>(res: ApiCommonResponse<T>): T` 는 **`@/shared/api/page`** 에서 import(http 가 아님). 따라서 `http` 호출에 `<ApiCommonResponse<T>>` 제네릭 필수.

```ts
import { http } from "@/shared/api/http";
import { unwrap, type ApiCommonResponse } from "@/shared/api/page"; // 실제 export 경로/이름 확인(song-packs-api.ts 헤더 복사)
import type { AvatarCatalogItem, BotRosterItem } from "@/entities/virtual-dj";

// avatar-catalog-api.ts
export async function getAvatarCatalog(): Promise<AvatarCatalogItem[]> {
  const res = await http<ApiCommonResponse<AvatarCatalogItem[]>>("/api/v1/admin/virtual-dj/avatar-catalog");
  return unwrap(res);
}
// bots-api.ts
export async function getBots(): Promise<BotRosterItem[]> {
  const res = await http<ApiCommonResponse<BotRosterItem[]>>("/api/v1/admin/virtual-dj/bots");
  return unwrap(res);
}
export async function setBotAvatar(userId: number, avatarBodyUri: string): Promise<void> {
  await http<void>(`/api/v1/admin/virtual-dj/bots/${userId}/avatar`, { method: "PUT", body: { avatarBodyUri } });
}
type DistributeResult = { assigned: { userId: number; avatarBodyUri: string }[] };
export async function distributeAvatars(botIds: number[], bodyUris: string[]): Promise<DistributeResult> {
  const res = await http<ApiCommonResponse<DistributeResult>>(
    "/api/v1/admin/virtual-dj/bots/avatar/distribute", { method: "POST", body: { botIds, bodyUris } });
  return unwrap(res);
}
```

- [ ] **Step 3: react-query 훅** — `useAvatarCatalog`(staleTime 길게, 정적), `useBots`(`["virtual-dj","bots"]`), `useSetBotAvatar`/`useDistributeAvatars`(성공 시 `["virtual-dj","bots"]` invalidate). 기존 `use-provision-pool.ts` 패턴 미러.
- [ ] **Step 4: 단위 테스트(MSW)** — 각 api 함수 요청 형태/언랩. → `yarn vitest run <paths>` PASS
- [ ] **Step 5: 커밋** — `feat(가상DJ P1): 봇 아바타 카탈로그/로스터/배분 API 클라이언트 (#<admin-issue>)`
  > **NOTE (executor):** 프론트 작업 시작 시 **pfplay-admin 레포에 한글 GitHub 이슈 먼저 등록**([[feedback_korean_issue_commit_pr]]) 후 그 번호를 커밋에 사용. pfplay-admin 은 GHA 없음([[reference_pfplay_admin_no_gha]]).

### Task 4.2: boundary 매퍼 + 아바타 피커 컴포넌트

**Files:**
- Create: `src/features/virtual-dj-pool/model/to-avatar-option.ts` + 테스트
- Create: `src/features/virtual-dj-pool/ui/avatar-picker.tsx` + 테스트

- [ ] **Step 1: 매퍼 실패 테스트** (`to-pack-track.test.ts` 패턴 — 명시 매핑·빈값 없음·source 어휘 누수 가드)

```ts
it("각 필드 매핑 + source 어휘 누수 없음", () => {
  const out = toAvatarOption({ bodyUri:"u", name:"n", thumbnailUri:"t", combinable:true, obtainableType:"BASIC" });
  expect(out.value).toBe("u"); expect(out.label).toBe("n"); expect(out.thumbnail).toBe("t");
  expect((out as any).bodyUri).toBeUndefined();
});
```

- [ ] **Step 2: 매퍼 구현**

```ts
export interface AvatarOption { value: string; label: string; thumbnail: string; combinable: boolean; tier: string | null; }
export function toAvatarOption(c: AvatarCatalogItem): AvatarOption {
  return { value: c.bodyUri, label: c.name, thumbnail: c.thumbnailUri, combinable: c.combinable, tier: c.obtainableType };
}
```

- [ ] **Step 3: 피커 컴포넌트** — 썸네일 그리드. props: `mode: "single"|"multi"`, `value`, `onChange`. multi=체크 토글(셋), single=라디오. combinable/standalone 배지(선택). `useAvatarCatalog()` 사용.
- [ ] **Step 4: 피커 테스트(MSW)** — 카탈로그 렌더, multi 다중선택 토글, single 단일선택. PASS
- [ ] **Step 5: 커밋** — `feat(가상DJ P1): 아바타 피커 + boundary 매퍼`

---

## Chunk 5: 프론트(pfplay-admin) — 로스터 UI + 일괄/개별 다이얼로그

### Task 5.1: 봇 로스터 섹션 (풀 페이지)

**Files:**
- Create: `src/features/virtual-dj-pool/ui/bot-roster.tsx` + 테스트
- Modify: `src/features/virtual-dj-pool/ui/pool-page-content.tsx` (로스터 섹션 추가)

- [ ] **Step 1: 로스터 테스트(MSW)** — `getBots` mock → 행마다 바디 썸네일(`avatarBodyUri`)·닉네임·배치룸·체크박스·"아바타 변경" 버튼 렌더. 아이콘 non-null 썸네일 표시.
- [ ] **Step 2: 로스터 구현** — 체크박스 다중선택 state(`selectedBotIds`), 선택>0 시 툴바("아바타 일괄 변경"). 기존 `bulk-action-toolbar` 스타일 계승.
- [ ] **Step 3: 풀 페이지 통합** — 요약 카드 아래 `<BotRoster/>` 추가(기존 테스트 회귀 없게 추가만).
- [ ] **Step 4: 테스트 GREEN** → 커밋 `feat(가상DJ P1): 봇 로스터 섹션 (풀 페이지)`

### Task 5.2: 일괄 배분 다이얼로그

**Files:**
- Create: `src/features/virtual-dj-pool/ui/distribute-avatars-dialog.tsx` + 테스트
- Create: `src/features/virtual-dj-pool/model/distribute-schema.ts`

- [ ] **Step 1: zod 스키마** — `botIds` non-empty, `bodyUris`(셋) non-empty.
- [ ] **Step 2: 다이얼로그 테스트(MSW)** — 선택 봇 N + 피커(multi)로 셋 선택 → 제출 시 `POST distribute {botIds, bodyUris}` 바디 정확 + 빈 셋 차단(제출 비활성/에러). 성공 시 `["virtual-dj","bots"]` invalidate.
- [ ] **Step 3: 다이얼로그 구현** — `AvatarPicker mode="multi"` + `useDistributeAvatars`. P2 `virtual-dj-bulk-dialog` UX(닫기 reset, pending 비활성, 결과 요약) 계승.
- [ ] **Step 4: 테스트 GREEN** → 커밋 `feat(가상DJ P1): 아바타 일괄 배분 다이얼로그`

### Task 5.3: 개별 편집 다이얼로그 + 전체 GREEN

**Files:**
- Create: `src/features/virtual-dj-pool/ui/set-bot-avatar-dialog.tsx` + 테스트

- [ ] **Step 1: 다이얼로그 테스트(MSW)** — 행 "아바타 변경" → 피커(single) → `PUT bots/{userId}/avatar {avatarBodyUri}` 정확. 성공 invalidate.
- [ ] **Step 2: 구현** — `AvatarPicker mode="single"` + `useSetBotAvatar`.
- [ ] **Step 3: 전체 프론트 검증**

Run: `yarn vitest run` / `yarn tsc --noEmit` / `yarn lint`
Expected: 전부 GREEN/clean (기존 582+ 테스트 + 신규)

- [ ] **Step 4: 커밋** — `feat(가상DJ P1): 봇 개별 아바타 편집 다이얼로그 + 전체 GREEN`

---

## Chunk 6: 로컬 docker-compose 풀스택 e2e 게이트 (dev 머지 전 필수)

> [[feedback_local_e2e_before_dev_merge]] — 단위/통합 GREEN ≠ 배포안전. **둘 다(백엔드+프론트) 로컬 기동 후 실 HTTP 흐름 검증.** [[reference_local_docker_compose]] / [[reference_pfplay_web_local_dev_http_webpack]]/admin 로컬 절차.

- [ ] **Step 1: 백엔드 기동** — `docker-compose.local.yml` + `.env.local`(`local` profile, 8080). **validate 부팅 확인**(P1은 마이그레이션 없으나 엔티티 정합·부팅 회귀 확인). app 로그 `Started ... Application` + 에러 없음.
- [ ] **Step 2: 어드민 기동** — pfplay-admin 로컬 dev 서버. admin seed 로그인(`admin@pfplay.local`).
- [ ] **Step 3: 카탈로그** — `GET /api/v1/admin/virtual-dj/avatar-catalog` (UI 피커 또는 curl) → published 바디 15종 반환 확인.
- [ ] **Step 4: provision 검증(핵심)** — 봇 풀 N명 생성 → `GET /virtual-dj/bots` 로스터 **전원 `avatarIconUri` non-null** 확인(P1-D5/null-icon 갭 제거 종단 증명).
- [ ] **Step 5: 일괄 배분** — 봇 다중선택 → 셋(예: standalone 4 + DJ_PNT 몇) 선택 → distribute → 로스터 아바타가 봇별로 달라졌는지 확인.
- [ ] **Step 6: 개별 변경** — 1봇 개별 변경 반영 확인.
- [ ] **Step 7: (가능 시) 룸 렌더** — MANAGED 방 배치 → pfplay-web 또는 API(`activeDjSnapshot`)에서 봇 아바타 변별 확인. 불가 시 API 레벨로 대체.
- [ ] **Step 8: 무NPE/무500 확인** — app 로그에 P1 경로 예외 없음.
- [ ] **Step 9: 결과 사용자 보고** — e2e 결과 요약. **GREEN 후에만** 백엔드 PR dev 머지 + 프론트 PR dev 머지 진행(승격은 P1+P3 후 일괄, 메모리 정책).

---

## 완료 기준 (Definition of Done)

1. 백엔드: `BotAvatarAssigner`(합성 규칙 단일 소스) + Randomizer/ApplyPort + 로스터 쿼리 + 어드민 4 엔드포인트 + provision 자동부여. `:app:test`+`:app:integrationTest`+`:user:test` GREEN. ArchUnit GREEN. 마이그레이션 없음.
2. 프론트: 로스터 + 피커 + 일괄/개별 다이얼로그. `vitest`+`tsc`+`lint` GREEN.
3. **로컬 docker-compose 풀스택 e2e GREEN** (provision 후 icon non-null + 배분 반영 + 무NPE).
4. 두 PR 한글 커밋/이슈(#288), dev 머지(사용자 게이트 아님 — e2e GREEN 후 자율). **release/main 승격은 보류**(P1+P3 후 #283~ 묶음 일괄).
