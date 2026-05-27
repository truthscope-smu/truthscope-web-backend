package com.truthscope.web.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** 아키텍처 규칙 자동 검증 — 레이어 의존성, 네이밍, 금지 패턴 */
@AnalyzeClasses(
    packages = "com.truthscope.web",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  // ── 레이어 의존성 ──

  @ArchTest
  static final ArchRule layerDependencies =
      layeredArchitecture()
          .consideringAllDependencies()
          .optionalLayer("Controller")
          .definedBy("..controller..")
          .optionalLayer("Service")
          .definedBy("..service..")
          .optionalLayer("Repository")
          .definedBy("..repository..")
          .optionalLayer("Entity")
          .definedBy("..entity..")
          .optionalLayer("DTO")
          .definedBy("..dto..")
          .optionalLayer("Converter")
          .definedBy("..converter..")
          .optionalLayer("Config")
          .definedBy("..config..")
          .optionalLayer("Exception")
          .definedBy("..exception..")
          .optionalLayer("Adapter")
          .definedBy("..adapter..")
          .whereLayer("Controller")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Service")
          .mayOnlyBeAccessedByLayers("Controller", "Adapter")
          .whereLayer("Repository")
          .mayOnlyBeAccessedByLayers("Service");

  // ── 네이밍 규칙 (패키지가 비어있으면 통과) ──

  @ArchTest
  static final ArchRule controllerNaming =
      classes()
          .that()
          .resideInAPackage("..controller..")
          .should()
          .haveSimpleNameEndingWith("Controller")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule serviceNaming =
      classes()
          .that()
          .resideInAPackage("..service..")
          .and()
          .arePublic()
          .and()
          .areTopLevelClasses()
          .should()
          .haveSimpleNameEndingWith("Service")
          .allowEmptyShould(true)
          .because(
              "Service stereotype 네이밍은 public top-level 클래스에만 적용. inner record/handler/private utility는 구현 세부 사항으로 exempt.");

  @ArchTest
  static final ArchRule repositoryNaming =
      classes()
          .that()
          .resideInAPackage("..repository..")
          .should()
          .haveSimpleNameEndingWith("Repository")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule converterNaming =
      classes()
          .that()
          .resideInAPackage("..converter..")
          .should()
          .haveSimpleNameEndingWith("Converter")
          .allowEmptyShould(true);

  // ── 금지 패턴 ──

  @ArchTest
  static final ArchRule controllerShouldNotAccessRepository =
      noClasses()
          .that()
          .resideInAPackage("..controller..")
          .should()
          .accessClassesThat()
          .resideInAPackage("..repository..");

  @ArchTest
  static final ArchRule entityShouldNotDependOnOtherLayers =
      noClasses()
          .that()
          .resideInAPackage("..entity..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..controller..", "..service..", "..repository..", "..dto..");

  // ── DDD invariant: entity는 setter 금지 (always-valid 모델) ──

  @ArchTest
  static final ArchRule entityShouldNotExposeSetters =
      noMethods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.truthscope.web.entity")
          .should()
          .haveNameStartingWith("set")
          .because(
              "Entity 변경은 비즈니스 메서드로만 허용 (DDD always-valid 모델). enum 패키지는 별도(..entity.enums..)이므로 영향 없음.");

  // ── core 모듈 격리 (ADR-006 D1: OSS 단독 배포 가능성 강제) ──

  @ArchTest
  static final ArchRule corePackagesShouldNotDependOnAppLayer =
      noClasses()
          .that()
          .resideInAnyPackage("..entity..", "..dto..", "..converter..", "..exception..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..controller..",
              "..service..",
              "..repository..",
              "..config..",
              "..security..",
              "..html..")
          .allowEmptyShould(true)
          .because(
              "ADR-006 D1: core 모듈(entity/dto/converter/exception)은 app 모듈에 의존 금지 — core jar가 OSS 단독 배포 가능해야 함. "
                  + "adapter 패키지는 모듈에 따라 위치가 다름 — input port 구현체는 app 모듈(service/security 의존 OK), "
                  + "외부 데이터소스 인터페이스는 core 모듈에 위치할 수 있음. core/adapter 추가 시 별도 룰로 보호 (BE #22 진입 시 갱신).");

  // ── DTO record 강제 (CONVENTIONS: 요청 DTO는 record 타입) ──

  @ArchTest
  static final ArchRule requestDtoShouldBeRecord =
      classes()
          .that()
          .resideInAPackage("..dto.request..")
          .should()
          .beRecords()
          .allowEmptyShould(true)
          .because(
              "CONVENTIONS: 요청 DTO는 record 타입 (Java 17+) — immutable + 자동 equals/hashCode/toString.");

  // ── BYOK userApiKey log redaction (ADR-004 §f, BE #90 트랙 4) ──

  @ArchTest
  static final ArchRule claimAnalysisServiceMustUseKeyFingerprinter =
      classes()
          .that()
          .haveSimpleName("ClaimAnalysisService")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.truthscope.web.audit.KeyFingerprinter")
          .allowEmptyShould(true)
          .because(
              "ADR-004 §f BYOK audit fingerprint 강제 — ClaimAnalysisService는 userApiKey 원본 대신 KeyFingerprinter.fingerprint() 결과만 audit에 박제. 의존 부재 시 원본 키 누출 path 발생.");

  @ArchTest
  static final ArchRule byokClassesShouldNotHaveSlf4jLoggerField =
      fields()
          .that()
          .areDeclaredInClassesThat()
          .haveSimpleName("ClaimAnalysisService")
          .or()
          .areDeclaredInClassesThat()
          .haveSimpleName("GeminiClient")
          .should()
          .notHaveRawType("org.slf4j.Logger")
          .allowEmptyShould(true)
          .because(
              "ADR-004 §f userApiKey log redaction — BYOK 처리 클래스(ClaimAnalysisService + GeminiClient)는 SLF4J Logger field 금지. Logger field 부재로 userApiKey 원본 log 박제 path 정적 차단. logging 필요 시 AOP @Around 분리 또는 KeyFingerprinter 결과만 박제.");

  // ── PromptShield 접근 제한 (BE #72 S3-02) ──

  /**
   * PromptShield 는 Service 레이어에서만 접근 가능. Service 외 모든 패키지(Controller/Repository/Config 등)가 prompt
   * 패키지에 의존하면 프롬프트 조립 로직이 부적절한 레이어에 노출되어 레이어 위반. prompt 패키지 내부 자기 의존은 허용.
   *
   * <p>CodeRabbit 리뷰 반영 (Service 외 패키지 전체 차단 확대) — 본래 Controller 만 차단했으나 Repository/Config 등이
   * prompt 의존하는 경로도 차단해야 정합.
   */
  @ArchTest
  static final ArchRule promptComponentAccessRule =
      noClasses()
          .that()
          .resideOutsideOfPackage("..service..")
          .and()
          .resideOutsideOfPackage("..prompt..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..prompt..")
          .allowEmptyShould(true)
          .because(
              "BE #72 S3-02 (CR review fix): PromptShield 는 Service 레이어에서만 호출. Service 외 패키지"
                  + "(Controller/Repository/Config 등)가 prompt 패키지에 의존 시 프롬프트 조립 로직이 부적절한 레이어에 노출. "
                  + "prompt 패키지 내부 자기 의존은 허용.");

  /**
   * prompt 패키지 클래스는 @Service 가 아닌 @Component — serviceNaming 룰 적용 면제 박제. 이 룰 자체는 검증 룰이 아니라 의도 박제용 —
   * allowEmptyShould(true) 유지.
   */
  @ArchTest
  static final ArchRule promptPackageNamingExempt =
      classes()
          .that()
          .resideInAPackage("..prompt..")
          .should()
          .notBeAnnotatedWith(org.springframework.stereotype.Service.class)
          .allowEmptyShould(true)
          .because(
              "BE #72: prompt 패키지 클래스는 @Component (NOT @Service) — serviceNaming 룰"
                  + " 적용 대상에서 자동 제외됨을 코드 레벨에서 명문화.");
}
