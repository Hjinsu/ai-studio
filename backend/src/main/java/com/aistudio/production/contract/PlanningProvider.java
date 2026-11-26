package com.aistudio.production.contract;

import com.aistudio.production.contract.ProductionPlanning.Research;
import com.aistudio.production.contract.ProductionPlanning.ResearchInput;
import com.aistudio.production.contract.ProductionPlanning.ScenePlan;
import com.aistudio.production.contract.ProductionPlanning.ScenesInput;
import com.aistudio.production.contract.ProductionPlanning.Script;
import com.aistudio.production.contract.ProductionPlanning.ScriptInput;

/** Invoked only after the cost port commits dispatch permission. V2-03 uses FAKE only. */
public interface PlanningProvider {
    Research research(ResearchInput input);
    Script writeScript(ScriptInput input);
    ScenePlan planScenes(ScenesInput input);
}
