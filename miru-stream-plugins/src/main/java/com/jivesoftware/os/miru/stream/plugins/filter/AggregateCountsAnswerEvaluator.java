package com.jivesoftware.os.miru.stream.plugins.filter;

import com.jivesoftware.os.miru.plugin.solution.MiruAnswerEvaluator;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLog;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLogLevel;
import java.util.Map;

/**
 *
 */
public class AggregateCountsAnswerEvaluator implements MiruAnswerEvaluator<AggregateCountsAnswer> {

    private final AggregateCountsQuery query;

    public AggregateCountsAnswerEvaluator(AggregateCountsQuery query) {
        this.query = query;
    }

    @Override
    public boolean isDone(AggregateCountsAnswer answer, MiruSolutionLog solutionLog) {
        solutionLog.log(MiruSolutionLogLevel.INFO, "Results exhausted = {}", answer.resultsExhausted);
        if (answer.resultsExhausted) {
            return true;
        }
        for (Map.Entry<String, AggregateCountsAnswerConstraint> entry : answer.constraints.entrySet()) {
            AggregateCountsQueryConstraint constraint = query.constraints.get(entry.getKey());
            int requiredDistincts = constraint.desiredNumberOfDistincts + constraint.startFromDistinctN;
            AggregateCountsAnswerConstraint answerConstraint = entry.getValue();
            solutionLog.log(MiruSolutionLogLevel.INFO, "Evaluate {} {} >= {}", entry.getKey(), answerConstraint.collectedDistincts, requiredDistincts);
            if (answerConstraint.collectedDistincts < requiredDistincts) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean stopOnUnsolvablePartition() {
        return true;
    }

    @Override
    public boolean useParallelSolver() {
        return false;
    }
}
