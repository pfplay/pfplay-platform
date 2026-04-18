package com.pfplaybackend.api.user.application.service.initialize;

import com.pfplaybackend.api.user.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.AvatarIconResourceRepository;
import com.pfplaybackend.api.user.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.user.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.user.domain.entity.data.AvatarIconResourceData;
import com.pfplaybackend.api.user.domain.enums.ObtainmentType;
import com.pfplaybackend.api.user.domain.enums.PairType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AvatarResourceInitializeService {
    private final AvatarBodyResourceRepository avatarBodyResourceRepository;
    private final AvatarFaceResourceRepository avatarFaceResourceRepository;
    private final AvatarIconResourceRepository avatarIconResourceRepository;

    @Transactional
    public void addAvatarBodies() {
        // 기본 아바타 Body 리소스 추가
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_basic_001").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media").obtainableType(ObtainmentType.BASIC).obtainableScore(0).isCombinable(true).isDefaultSetting(true).combinePositionX(60).combinePositionY(41).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_basic_002").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_002.png?alt=media").obtainableType(ObtainmentType.BASIC).obtainableScore(0).isCombinable(false).isDefaultSetting(false).combinePositionX(0).combinePositionY(0).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_basic_003").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_003.png?alt=media").obtainableType(ObtainmentType.BASIC).obtainableScore(0).isCombinable(false).isDefaultSetting(false).combinePositionX(0).combinePositionY(0).build());
        // 디제잉 해금 아바타 Body 리소스 추가
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_djing_001").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_001.png?alt=media").obtainableType(ObtainmentType.DJ_PNT).obtainableScore(10).isCombinable(false).isDefaultSetting(false).combinePositionX(0).combinePositionY(0).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_djing_002").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_002.png?alt=media").obtainableType(ObtainmentType.DJ_PNT).obtainableScore(25).isCombinable(false).isDefaultSetting(false).combinePositionX(0).combinePositionY(0).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_djing_003").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_003.png?alt=media").obtainableType(ObtainmentType.DJ_PNT).obtainableScore(60).isCombinable(true).isDefaultSetting(false).combinePositionX(60).combinePositionY(39).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_djing_004").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_004.png?alt=media").obtainableType(ObtainmentType.DJ_PNT).obtainableScore(100).isCombinable(true).isDefaultSetting(false).combinePositionX(60).combinePositionY(45).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_djing_005").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_005.png?alt=media").obtainableType(ObtainmentType.DJ_PNT).obtainableScore(150).isCombinable(true).isDefaultSetting(false).combinePositionX(60).combinePositionY(40).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_djing_006").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_006.png?alt=media").obtainableType(ObtainmentType.DJ_PNT).obtainableScore(200).isCombinable(true).isDefaultSetting(false).combinePositionX(52).combinePositionY(39).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_djing_007").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_007.png?alt=media").obtainableType(ObtainmentType.DJ_PNT).obtainableScore(500).isCombinable(true).isDefaultSetting(false).combinePositionX(60).combinePositionY(40).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_djing_008").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_008.png?alt=media").obtainableType(ObtainmentType.DJ_PNT).obtainableScore(1000).isCombinable(true).isDefaultSetting(false).combinePositionX(60).combinePositionY(40).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_djing_009").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_009.png?alt=media").obtainableType(ObtainmentType.DJ_PNT).obtainableScore(2000).isCombinable(true).isDefaultSetting(false).combinePositionX(60).combinePositionY(42).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_djing_010").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_010.png?alt=media").obtainableType(ObtainmentType.DJ_PNT).obtainableScore(4000).isCombinable(true).isDefaultSetting(false).combinePositionX(58).combinePositionY(43).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_djing_011").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_011.png?alt=media").obtainableType(ObtainmentType.DJ_PNT).obtainableScore(7000).isCombinable(true).isDefaultSetting(false).combinePositionX(60).combinePositionY(44).build());
        addAvatarBody(AvatarBodyResourceData.builder().name("ava_body_djing_012").resourceUri("https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_djing%2Fava_djing_012.png?alt=media").obtainableType(ObtainmentType.DJ_PNT).obtainableScore(10000).isCombinable(true).isDefaultSetting(false).combinePositionX(56).combinePositionY(38).build());
    }

    @Transactional
    public void addAvatarFaces() {
        addAvatarFace("ava_face_basic_001", "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_face%2Fava_face_001.png?alt=media");
    }

    @Transactional
    public void addAvatarIcons() {
        addAvatarIcon("ava_icon_face_basic_001", "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_icon%2Fava_icon_face_basic_001.png?alt=media", PairType.FACE);
        addAvatarIcon("ava_icon_body_basic_002", "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_icon%2Fava_icon_body_basic_002.png?alt=media", PairType.BODY);
        addAvatarIcon("ava_icon_body_basic_003", "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_icon%2Fava_icon_body_basic_003.png?alt=media", PairType.BODY);
        addAvatarIcon("ava_icon_body_djing_001", "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_icon%2Fava_icon_body_djing_001.png?alt=media", PairType.BODY);
        addAvatarIcon("ava_icon_body_djing_002", "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_icon%2Fava_icon_body_djing_002.png?alt=media", PairType.BODY);
    }

    private void addAvatarBody(AvatarBodyResourceData template) {
        Optional<AvatarBodyResourceData> existing = avatarBodyResourceRepository.findByName(template.getName());
        if (existing.isPresent()) {
            AvatarBodyResourceData data = existing.get();
            data.updateResource(template.getResourceUri(), template.getObtainableType(),
                    template.getObtainableScore(), template.isCombinable(),
                    template.isDefaultSetting(), template.getCombinePositionX(), template.getCombinePositionY());
            avatarBodyResourceRepository.save(data);
        } else {
            avatarBodyResourceRepository.save(template);
        }
    }

    private void addAvatarFace(String name, String resourceUri) {
        if (avatarFaceResourceRepository.findByResourceUri(resourceUri).isPresent()) {
            return;
        }
        AvatarFaceResourceData avatarFaceResourceData = AvatarFaceResourceData.create(name, resourceUri);
        avatarFaceResourceRepository.save(avatarFaceResourceData);
    }

    private void addAvatarIcon(String name, String resourceUri, PairType pairType) {
        if (avatarIconResourceRepository.findByNameAndPairType(name, pairType) != null) {
            return;
        }
        AvatarIconResourceData avatarIconResourceData = AvatarIconResourceData.create(name, resourceUri, pairType);
        avatarIconResourceRepository.save(avatarIconResourceData);
    }
}
