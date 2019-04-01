package org.jetlinks.rule.engine.singleton;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.rule.engine.api.*;
import org.jetlinks.rule.engine.api.executor.ExecutableRuleNode;
import org.jetlinks.rule.engine.api.executor.ExecutableRuleNodeFactory;
import org.jetlinks.rule.engine.api.model.Condition;
import org.jetlinks.rule.engine.api.model.RuleLink;
import org.jetlinks.rule.engine.api.model.RuleNodeModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单点规则引擎实现
 *
 * @author zhouhao
 * @since 1.0.0
 */
@Slf4j
public class SingletonRuleEngine implements RuleEngine {

    @Getter
    @Setter
    private ExecutableRuleNodeFactory nodeFactory;

    @Getter
    @Setter
    private ConditionEvaluator evaluator;

    public Map<String, RuleInstanceContext> contextMap = new ConcurrentHashMap<>();

    public RuleExecutor createSingleRuleExecutor(Condition condition, RuleNodeModel nodeModel) {
        ExecutableRuleNode ruleNode = nodeFactory.create(nodeModel.createConfiguration());
        Logger logger = new Slf4jLogger("rule.engine.node." + nodeModel.getId());
        SimpleRuleExecutor executor = new SimpleRuleExecutor(ruleNode, logger);
        if (null != condition) {
            executor.setCondition(ruleData -> {
                Map<String, Object> context = new HashMap<>();
                context.put("ruleData", ruleData);
                context.put("data", ruleData.getData());
                return Boolean.TRUE.equals(evaluator.evaluate(condition, context));
            });
        }
        //event
        for (RuleLink output : nodeModel.getEvents()) {
            for (RuleNodeModel ruleNodeModel : output.getTarget()) {
                executor.addEventListener(output.getType(), createRuleExecutor(output.getCondition(), ruleNodeModel, null));
            }
        }
        executor.setNodeType(nodeModel.getNodeType());
        return executor;
    }

    public RuleExecutor createRuleExecutor(Condition condition, RuleNodeModel nodeModel, RuleExecutor parent) {
        RuleExecutor executor = createSingleRuleExecutor(condition, nodeModel);
        if (parent != null) {
            parent.addNext(executor);
        }

        //output
        for (RuleLink output : nodeModel.getOutputs()) {
            for (RuleNodeModel ruleNodeModel : output.getTarget()) {
                createRuleExecutor(output.getCondition(), ruleNodeModel, executor);
            }
        }

        return parent != null ? parent : executor;
    }


    @Override
    public RuleInstanceContext startRule(Rule rule) {
        String id = IDGenerator.MD5.generate();
        RuleNodeModel nodeModel = rule.getModel().getStartNode()
                .orElseThrow(() -> new UnsupportedOperationException("无法获取启动节点"));
        SingletonRuleInstanceContext context = new SingletonRuleInstanceContext();
        context.id = id;
        context.startTime = System.currentTimeMillis();
        context.rootExecutor = createRuleExecutor(null, nodeModel, null);
        contextMap.put(id, context);
        return context;
    }

    @Override
    public RuleInstanceContext getInstance(String instanceId) {
        return contextMap.get(instanceId);
    }

    @Getter
    @Setter
    public static class SingletonRuleInstanceContext implements RuleInstanceContext {
        private String id;
        private long   startTime;

        private RuleExecutor rootExecutor;

        @Override
        public CompletionStage<RuleData> execute(RuleData data) {
            if (!rootExecutor.should(data)) {
                return CompletableFuture.completedFuture(null);
            }
            return rootExecutor.execute(data);
        }

        @Override
        public void stop() {

        }
    }
}
