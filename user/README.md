# User Module — User Profile Context

> 실사 기준: 2026-07-31 (`origin/develop`).

## Bounded Context

사용자 신원과 프로필 도메인. 회원(Member)/게스트(Guest) 등록, 프로필, 아바타 **선택**, 지갑,
활동 점수를 담당한다.

> **아바타 카탈로그는 이 모듈이 아니다.** 리소스(body/face) 소유·발행은 `avatar` 모듈이며
> (V12 에서 분리), 이 모듈은 "어떤 아바타를 골랐는가"만 프로필에 들고 있다.

## 책임

- 회원(OAuth)/게스트 등록 및 관리
- 프로필(닉네임, 바이오, 아바타 조합 설정)
- 아바타 **선택** 및 조합 유효성 검증
- 지갑, 활동 점수(DJ 포인트) 추적
- 닉네임 유효성 검증 (Passay)

## 핵심 엔티티

| 엔티티 | 비즈니스 로직 |
|---|---|
| `UserAccountData` | 계정 (이메일, OAuth provider), 탈퇴(soft-delete) |
| `MemberData` | OAuth 회원 — 프로필/활동 생성, 바이오 수정 |
| `GuestData` | 임시 익명 사용자 |
| `ProfileData` | 프로필 (닉네임, 아바타 조합 설정, 바이오) |
| `ActivityData` | 활동 점수 |

> ⚠️ **탈퇴 시 email 만 익명화되고 `ProfileData.nickname` 은 남는다.** `user_profile.nickname` 에
> UNIQUE(V15)가 걸려 있어 그 닉네임은 영구 결번이 된다
> ([#339](https://github.com/pfplay/pfplay-platform/issues/339)).

## 제공하는 Port (구현은 `app/bootstrap`)

| Port | 용도 | 구현체 |
|---|---|---|
| `PlaylistSetupPort` | 회원가입 시 기본 플레이리스트 생성 | `PlaylistSetupAdapter` |
| `OAuth2RedirectPort` | OAuth2 리다이렉트 URI 생성 | `OAuth2RedirectAdapter` |

## 소비하는 외부 Port

`avatar` 모듈의 `AvatarCatalogQueryUseCase` (아바타 피커용 카탈로그 조회).
그 외 cross-module 의존은 포트 인터페이스만 정의하고 구현은 `app` 이 제공한다.

## Application Service

| Service | 역할 |
|---|---|
| `MemberSignService` | OAuth 회원 등록/로그인 (프로필·활동·기본 플레이리스트 초기화) |
| `GuestSignService` | 게스트 생성/재사용 |
| `UserProfileCommandService` / `UserProfileQueryService` | 프로필 수정 / 조회 |
| `UserAvatarCommandService` / `UserAvatarQueryService` | 아바타 선택 / 조회 |
| `UserBioCommandService` | 바이오 수정 |
| `UserInfoQueryService` | 사용자 정보 조회 |
| `UserWalletCommandService` | 지갑 |
| `UserActivityCommandService` | 활동 점수 갱신 |
| `AvatarResourceQueryService` | avatar 모듈 카탈로그 조회 파사드 |

명령/조회 서비스가 이름으로 분리돼 있다(CQRS-lite). 신규 서비스도 이 규칙을 따른다.

## Domain Service

| Service | 역할 |
|---|---|
| `UserAvatarDomainService` | 아바타 조합 유효성 검증 |

## 도메인 이벤트

`MemberRegisteredEvent` · `MemberTierChangedEvent` · `UserProfileChangedEvent` ·
`UserAccountWithdrawnEvent`

## 의존 방향

```
user → avatar → common
user → common
```

## Authority Tier

| Tier | 의미 |
|---|---|
| **FM** | Full Member — OAuth 인증 완료 회원 |
| **AM** | Associate Member — 제한된 회원 |
| **GT** | Guest — 임시 게스트 |
