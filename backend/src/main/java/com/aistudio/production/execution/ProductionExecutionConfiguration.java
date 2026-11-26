package com.aistudio.production.execution;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.aistudio.production.contract.DiscoveryEvaluator;
import com.aistudio.production.contract.DiscoveryExecutionPort;
import com.aistudio.production.contract.PlanningExecutionPort;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.discovery.RuleBasedDiscoveryEvaluator;
import com.aistudio.production.planning.PlanningAvailability;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.production.stage.PlanningReview;
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
            ObjectProvider<StageWork> work, ExecutionProperties properties,
            ObjectProvider<DiscoveryExecutionPort> discoveryPort, ObjectProvider<DiscoveryEvaluator> evaluator,
            ObjectProvider<PlanningExecutionPort> planningPort, ObjectProvider<PlanningReview> review,
            PlanningAvailability planningAvailability) {
        DiscoveryExecutionPort port = discoveryPort.getIfAvailable();
        // V2-02: discovery requests are claimed only when the discovery persistence/cost adapter is present
        DiscoveryFlow flow = port == null ? null
                : new DiscoveryFlow(port, evaluator.getIfAvailable(RuleBasedDiscoveryEvaluator::new));
        PlanningFlow planningFlow = planningAvailability.ready()
                ? new PlanningFlow(planningPort.getObject(), review.getObject(), properties.fakeEffectMaxKrw())
                : null;
        return new ProductionExecutor(mapper, control, transactions, cost, selection,
                work.getIfAvailable(() -> new FakeStageWork(properties.fakeEffectMaxKrw())), properties, flow,
                planningFlow);
    }

    @Bean
    ProductionWorkerPool productionWorkerPool(ProductionExecutor executor, ExecutionProperties properties,
            Environment environment) {
        boolean cli = environment.getProperty("ai-studio.cli.admin", Boolean.class, false);
        return new ProductionWorkerPool(executor, properties, properties.autoStart() && !cli);
    }
}
