package com.aistudio.production.execution;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.shared.db.BudgetControlLock;
import com.aistudio.shared.db.LockRetryingTransactions;

/**
 * Wires the executor only when {@code ai-studio.production.executor.enabled=true}. Both common ports must then
 * be provided; there is no fallback that could dispatch effects outside the cost port. Workers never start in the
 * admin CLI.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExecutionProperties.class)
@ConditionalOnProperty(prefix = "ai-studio.production.executor", name = "enabled", havingValue = "true")
class ProductionExecutionConfiguration {

    @Bean
    ProductionExecutor productionExecutor(ExecutionMapper mapper, BudgetControlLock control,
            LockRetryingTransactions transactions, ProductionCostPort cost, ProductionSelectionPort selection,
            ObjectProvider<StageWork> work, ExecutionProperties properties) {
        return new ProductionExecutor(mapper, control, transactions, cost, selection,
                work.getIfAvailable(() -> new FakeStageWork(properties.fakeEffectMaxKrw())), properties);
    }

    @Bean
    ProductionWorkerPool productionWorkerPool(ProductionExecutor executor, ExecutionProperties properties,
            Environment environment) {
        boolean cli = environment.getProperty("ai-studio.cli.admin", Boolean.class, false);
        return new ProductionWorkerPool(executor, properties, properties.autoStart() && !cli);
    }
}
