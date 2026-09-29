package com.ga.disclosure.rules.template;

import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.rules.bundle.TemplateBundle;
import com.ga.disclosure.rules.resolve.ResolutionFailure;
import com.ga.disclosure.rules.resolve.RuleResolutionException;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.testing.InMemoryFormTemplatePort;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Phase 1 C10: 서식 단건 해석·Ambiguous, pendingConfirmation은 필수 필드에 산입되지 않는다. */
class TemplateResolverTest {

    private static final TenantId T = TenantId.of("T1");
    private static final LocalDate D = LocalDate.parse("2026-09-23");
    private static final TemplateBundle STANDARD = Bundles.template(Bundles.STANDARD_V1);

    @Test
    void resolvesTheSingleTemplateInForce() {
        TemplateResolution r = new TemplateResolver(new InMemoryFormTemplatePort().add(Bundles.template(STANDARD, null)))
                .resolve(T, TemplateType.STANDARD, D);
        assertThat(r.ref()).isEqualTo(STANDARD.template());
        assertThat(r.fields()).extracting(TemplateField::order).isSorted();
        assertThat(r.fields()).hasSize(9).allSatisfy(f -> assertThat(f.required()).isTrue());
        assertThat(r.requiredFieldCodes()).hasSize(9);
    }

    @Test
    void pendingConfirmationIsNotAField() {
        TemplateResolution r = TemplateResolver.resolution(Bundles.template(STANDARD, null));
        assertThat(r.pendingConfirmationRefs()).containsExactly("TODO(confirm#2)");
        assertThat(r.requiredFieldCodes()).noneMatch(code -> code.contains("TODO") || code.contains("confirm"));
        assertThat(r.fields()).extracting(TemplateField::code).doesNotContainAnyElementsOf(r.pendingConfirmationRefs());
    }

    @Test
    void noTemplateAndTwoTemplatesFail() {
        assertThat(catchThrowableOfType(RuleResolutionException.class,
                () -> new TemplateResolver(new InMemoryFormTemplatePort()).resolve(T, TemplateType.STANDARD, D)).failure())
                .isEqualTo(ResolutionFailure.NO_TEMPLATE);

        InMemoryFormTemplatePort two = new InMemoryFormTemplatePort()
                .add(Bundles.template(STANDARD, null))
                .add(Bundles.template(STANDARD, null));
        assertThat(catchThrowableOfType(RuleResolutionException.class,
                () -> new TemplateResolver(two).resolve(T, TemplateType.STANDARD, D)).failure())
                .isEqualTo(ResolutionFailure.AMBIGUOUS);
    }

    @Test
    void otherTypeAndOtherPeriodAreNotMatched() {
        InMemoryFormTemplatePort port = new InMemoryFormTemplatePort().add(Bundles.template(STANDARD, LocalDate.parse("2026-09-01")));
        assertThat(catchThrowableOfType(RuleResolutionException.class,
                () -> new TemplateResolver(port).resolve(T, TemplateType.STANDARD, D)).failure())
                .isEqualTo(ResolutionFailure.NO_TEMPLATE);
        assertThat(catchThrowableOfType(RuleResolutionException.class,
                () -> new TemplateResolver(port).resolve(T, TemplateType.AUTO, LocalDate.parse("2026-08-01"))).failure())
                .isEqualTo(ResolutionFailure.NO_TEMPLATE);
    }
}
