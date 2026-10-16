package io.oryxos.knowledge;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 仅供本模块的 {@code @DataJpaTest} 定位配置根；不承担任何生产装配。
 *
 * <p>实体与仓储都在 {@code oryxos-storage}（依赖方向：knowledge → storage），自动扫描根在 {@code io.oryxos.knowledge}
 * 够不到，故显式指过去。
 */
@SpringBootApplication
@EntityScan("io.oryxos.storage")
@EnableJpaRepositories("io.oryxos.storage")
class TestKnowledgeApplication {}
