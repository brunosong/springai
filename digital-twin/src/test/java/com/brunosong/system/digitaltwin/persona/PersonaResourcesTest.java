package com.brunosong.system.digitaltwin.persona;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PersonaResourcesTest {

    private final PersonaResources resources = new PersonaResources(JsonMapper.builder().build());

    @Test
    void facts_json_에서_이름을_읽는다() {
        assertThat(resources.fullName()).isEqualTo("송호식");
        assertThat(resources.name()).isEqualTo("브루노 송");
    }

    @Test
    void 이력서_PDF_가_있으면_한글_텍스트를_뽑아낸다() {
        assumeTrue(!PersonaResources.NO_RESUME.equals(resources.resume()), "이력서 PDF 가 없어 건너뛴다");

        assertThat(resources.resume()).contains("송호식");
    }
}
