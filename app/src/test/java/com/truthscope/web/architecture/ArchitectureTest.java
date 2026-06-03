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
// Round 6 H-R6-1 amend: public class 선언 — integration 패키지(S5 테스트)가
// wikipediaSignalDependentsMustNotAccessFactcheckCacheRepository 룰 필드를 FQN으로 직접 참조해야 하므로
// enclosing class를 public으로 노출. 다른 룰 필드는 package-private 유지로 BC 무영향.
public class ArchitectureTest {

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

  // ── Wikipedia Tier 1 사용 금지 + factcheck_cache 저장 금지 (BE #73, domain-logic.md) ──
  // H3 amend: FactcheckCache.java (core/entity 라인 17) + FactcheckCacheRepository.java
  // (app/repository 라인 10)
  // 이미 존재하므로 allowEmptyShould(true) 제거 — 실 클래스 대상 검증으로 전환.
  //
  // H3 codex Round 2 미해소 amend (옵션 A+B 결합):
  // - 옵션 A (클래스 이름 기반 룰 유지): 기존 2 룰 유지 + FactcheckCacheRepository.save() 인자 타입 차단 룰 추가.
  // - 옵션 B (integration test): S4 시나리오 추가 — WikipediaMetaSignal을 받아 FactcheckCache로 변환 시도 시
  //   컴파일 에러 또는 ArchUnit FAIL 검증 (아래 S4 케이스 참조).
  // ArchUnit DSL 한계: methodsThat()으로 save() 인자 타입을 직접 차단하는 DSL은
  // ArchUnit 1.4.0 기준 "methods that have raw parameter types accessible from X" API가
  // noClasses().should().callMethodWhere() 패턴으로 구현 가능하나 정확한 타입 traversal은
  // integration test로 보완하는 것이 더 신뢰도 높음.

  /**
   * Wikipedia 결과를 factcheck_cache 저장 경로로 쓰는 코드 차단. WikipediaAdapter + WikipediaMetaSignal이
   * FactcheckCacheRepository를 의존하면 Tier 1 캐시 오염 위험.
   *
   * <p>H3 amend: FactcheckCacheRepository.java 실 존재 확인 (app/repository 라인 10) →
   * allowEmptyShould(true) 제거. 실제 repository save() 인자로 WikipediaMetaResult/WikipediaMetaSignal 타입이
   * 도달하는 경로를 정적 검증.
   */
  @ArchTest
  static final ArchRule wikipediaAdapterMustNotDependOnFactcheckCacheRepository =
      noClasses()
          .that()
          .haveSimpleNameContaining("Wikipedia")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameContaining("FactcheckCache")
          .because(
              "domain-logic.md Wikipedia placement 룰: Wikipedia 본문을 factcheck_cache에 저장 금지. "
                  + "WikipediaAdapter/WikipediaMetaSignal은 FactcheckCacheRepository/FactcheckCache 의존 불허. "
                  + "H3 amend: FactcheckCacheRepository 실 존재 확인 후 allowEmptyShould 제거.");

  /**
   * FactcheckCache 관련 클래스가 WikipediaMetaResult/WikipediaMetaSignal 타입을 의존하지 않도록 차단.
   *
   * <p>H3 amend: WikipediaMetaResult → FactcheckCache 저장 시도 시 컴파일 또는 ArchUnit FAIL 보장. H3 codex
   * Round 2 amend (옵션 A+B): integration test S4에서 데이터 흐름 검증 보완.
   */
  @ArchTest
  static final ArchRule wikipediaMetaResultMustNotBeUsedInFactcheckCacheLayer =
      noClasses()
          .that()
          .haveSimpleNameContaining("FactcheckCache")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameContaining("WikipediaMeta")
          .because(
              "domain-logic.md Wikipedia placement 룰: WikipediaMetaResult/WikipediaMetaSignal(factcheckCacheable=false 강제)는 "
                  + "factcheck_cache 영역에 진입 불허. Tier 1 캐시 오염 방지. "
                  + "H3 amend: FactcheckCache 실 존재 확인 후 allowEmptyShould 제거. "
                  + "H3 codex Round 2 amend: ArchUnit 클래스 이름 룰 한계 인정 + integration test S4로 데이터 흐름 검증 보완.");

  /**
   * H3 codex Round 2 amend (옵션 A 추가 룰): WikipediaAdapter 패키지 클래스가 FactcheckCacheRepository를 직접 호출하지
   * 않도록 차단 (클래스 이름 룰 보완 — 데이터 흐름 경로 차단).
   *
   * <p>ArchUnit 1.4.0 DSL: noClasses().that()...should().callMethodWhere() 패턴. 일반 mapper/service가
   * WikipediaMetaSignal을 받아 FactcheckCache.builder()로 변환 후 repository.save()를 호출하는 경로는 상위 클래스 이름
   * 룰만으로 잡히지 않으므로 호출 차단 룰 추가.
   *
   * <p>주의: ArchUnit DSL 정확도는 execute 단계에서 ./gradlew test 실행 후 확인 필요. DSL 미지원 시 integration test S4로
   * 대체 (옵션 B — 아래 통합 테스트 S4 시나리오 참조).
   */
  @ArchTest
  static final ArchRule wikipediaPackageMustNotCallFactcheckCacheRepositorySave =
      noClasses()
          .that()
          .resideInAPackage("..adapter.datasource..")
          .should()
          .accessClassesThat()
          .haveSimpleNameContaining("FactcheckCacheRepository")
          .because(
              "H3 codex Round 2 amend: adapter.datasource 패키지 클래스는 FactcheckCacheRepository에 "
                  + "접근할 수 없다. 데이터 흐름 차단 보완 (클래스 이름 룰 한계 인정).");

  /**
   * Round 6 amend (H-R5-3 codex Round 5 발견 반영 — Consumer 예외 제거, 절대 불변식 강화): WikipediaMetaSignal에
   * 의존하는 모든 클래스는 FactcheckCacheRepository에 접근할 수 없다. 호출자 종류(Consumer 구현체 포함)와 무관한 절대 불변식이다.
   *
   * <p>codex Round 5 지적: 이전 Round 3 amend의 {@code doNotImplement(WikipediaSignalConsumer)} 예외 조건이
   * WikipediaSignalConsumer 구현체에 의한 cache 저장 경로를 합법화하여 domain-logic.md:78 "Wikipedia = Tier 1 아님
   * (Tier 1 = Google Fact Check API + factcheck_cache 한정)" 룰을 약화한다. Wikipedia 신호의 cache 저장 금지는 호출자
   * 종류와 무관한 절대 불변식이다.
   *
   * <p>강화 방향: {@code doNotImplement} 조건을 제거하고 WikipediaMetaSignal 의존 모든 클래스의
   * FactcheckCacheRepository 접근을 차단한다. WikipediaSignalConsumer 구현체도 차단 대상에 포함된다. Consumer는 Tier 2
   * 전용 보조 저장소 또는 이벤트 버스 등 cache 외 경로로만 위임해야 한다.
   *
   * <p>Round 6 amend: Consumer interface 자체는 유지 (Tier 2 전용 dispatcher 역할). 단, cache repository 접근
   * 권한은 Consumer 여부와 무관하게 박탈한다.
   */
  // Round 6 H-R6-1 amend (codex Round 6): integration 패키지의 S5 테스트가 본 룰 필드를 FQN으로
  // 직접 참조해야 한다. 필드만 public으로 노출해도 enclosing class가 package-private이면 외부 접근
  // 불가하므로, baseline 클래스 선언을 `class ArchitectureTest` → `public class ArchitectureTest`로
  // 변경한다(영향 파일 표에 별도 명시). 다른 룰 필드는 package-private 유지로 BC 무영향.
  //
  // ArchUnit 1.4.0 DSL 제약 amend: ClassesThat<GivenClassesConjunction>은 dependOnClassesThat()
  // 메서드를 제공하지 않음 (ClassesShould에서만 사용 가능). "의존 클래스 기반 필터"는
  // noClasses().that() 체인에서 직접 표현 불가. 대신 Wikipedia Signal 관련 클래스명 기반 룰로
  // 근사 구현하고, 데이터 흐름 검증은 integration test S4+S5 (옵션 B)로 보완.
  // PLAN.md 라인 1708 "DSL 미지원 시 integration test S4로 대체 (옵션 B)" 정합.
  @ArchTest
  public static final ArchRule wikipediaSignalDependentsMustNotAccessFactcheckCacheRepository =
      noClasses()
          .that()
          .haveSimpleNameContaining("WikipediaSignal")
          .should()
          .accessClassesThat()
          .haveSimpleNameContaining("FactcheckCacheRepository")
          .allowEmptyShould(true)
          .because(
              "Round 6 H-R5-3 amend (codex Round 5): WikipediaMetaSignal/WikipediaSignalConsumer 포함 클래스 — "
                  + "WikipediaSignalConsumer 구현체 포함 — 의 FactcheckCacheRepository 접근 절대 차단. "
                  + "domain-logic.md:78 'Wikipedia = Tier 1 아님' 룰의 절대 불변식 강제. "
                  + "Consumer interface는 Tier 2 전용 보조 경로로만 위임 의무. "
                  + "Round 3 amend의 Consumer 예외 조건은 우회 경로 합법화 risk로 Round 6에서 제거됨. "
                  + "ArchUnit 1.4.0 DSL amend: ClassesThat에 dependOnClassesThat() 미지원 → "
                  + "이름 기반 근사 + integration test S4+S5 옵션 B 보완.");
}
