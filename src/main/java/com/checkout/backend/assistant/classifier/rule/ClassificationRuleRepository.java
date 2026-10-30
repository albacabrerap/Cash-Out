package com.checkout.backend.assistant.classifier.rule;

import com.checkout.backend.assistant.classifier.rule.ClassificationRule.MatchType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassificationRuleRepository extends JpaRepository<ClassificationRule, Long> {

    Optional<ClassificationRule> findByUserIdAndMatchTypeAndPattern(Long userId, MatchType matchType, String pattern);

    List<ClassificationRule> findByUserIdAndMatchType(Long userId, MatchType matchType);

}
