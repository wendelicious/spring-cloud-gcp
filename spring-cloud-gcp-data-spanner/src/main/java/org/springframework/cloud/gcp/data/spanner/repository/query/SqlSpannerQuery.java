/*
 *  Copyright 2018 original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.springframework.cloud.gcp.data.spanner.repository.query;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;

import org.springframework.cloud.gcp.data.spanner.core.SpannerOperations;
import org.springframework.core.MethodParameter;
import org.springframework.data.repository.query.EvaluationContextProvider;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.Parameter;
import org.springframework.data.repository.query.Parameters;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.lang.Nullable;

/**
 * @author Balint Pato
 * @author Chengyuan Zhao
 */
public class SqlSpannerQuery implements RepositoryQuery {

	private final QueryMethod queryMethod;

	private final Class entityType;

	private final SpannerOperations spannerOperations;

	private final String sql;

	private final List<String> tags;

	private final Expression sqlExpression;

	private EvaluationContextProvider evaluationContextProvider;

	private SpelExpressionParser expressionParser;

	public SqlSpannerQuery(Class type, QueryMethod queryMethod,
			SpannerOperations spannerOperations, String sql,
			EvaluationContextProvider evaluationContextProvider,
			SpelExpressionParser expressionParser) {
		this.queryMethod = queryMethod;
		this.entityType = type;
		this.spannerOperations = spannerOperations;
		this.sql = sql;
		this.tags = getTags(sql);
		this.evaluationContextProvider = evaluationContextProvider;
		this.expressionParser = expressionParser;
		this.sqlExpression = detectExpression();
	}

	private List<String> getTags(String sql) {
		Pattern pattern = Pattern.compile("@\\S+");
		Matcher matcher = pattern.matcher(sql);
		List<String> tags = new ArrayList<>();
		while (matcher.find()) {
			// The initial '@' character must be excluded for Spanner
			tags.add(matcher.group().substring(1));
		}
		return tags;
	}

	@Override
	public Object execute(Object[] parameters) {
		return this.spannerOperations.find(this.entityType,
				SpannerStatementQueryExecutor.buildStatementFromSqlWithArgs(
						getSql(parameters), this.tags,
						filterOutSpelParameters(parameters)));
	}

	@VisibleForTesting
	String getSql(Object[] parameterValues) {
		EvaluationContext context = this.evaluationContextProvider
				.getEvaluationContext(this.queryMethod.getParameters(), parameterValues);
		return this.sqlExpression == null ? this.sql
				: this.sqlExpression.getValue(context, String.class);
	}

	@Override
	public QueryMethod getQueryMethod() {
		return this.queryMethod;
	}

	@VisibleForTesting
	Object[] filterOutSpelParameters(Object[] rawParams) {
		List<Object> filtered = new ArrayList<>();
		Parameters parameters = getQueryMethod().getParameters();
		for (int i = 0; i < parameters.getNumberOfParameters(); i++) {
			// non-SpEL args will not have a name here
			if (!getSpelParamName(parameters, i).isPresent()) {
				filtered.add(rawParams[i]);
			}
		}
		return filtered.toArray();
	}

	/*
	 * The underlying MethodParameter of Parameter is not directly accessible, so we
	 * cannot directly check for a SpEL name, and we must use reflection.
	 */
	@VisibleForTesting
	Optional<String> getSpelParamName(Parameters params, int index) {
		Parameter param = params.getParameter(index);
		for (Field methodParameterField : param.getClass().getDeclaredFields()) {
			if (methodParameterField.getType() == MethodParameter.class) {
				methodParameterField.setAccessible(true);
				try {
					MethodParameter methodParameter = (MethodParameter) methodParameterField
							.get(param);
					if (methodParameter.hasParameterAnnotation(Param.class)) {
						return param.getName();
					}
				}
				catch (IllegalAccessException e) {
					continue;
				}
			}
		}
		return Optional.empty();
	}

	@Nullable
	private Expression detectExpression() {
		Expression expression = this.expressionParser.parseExpression(this.sql,
				ParserContext.TEMPLATE_EXPRESSION);
		return expression instanceof LiteralExpression ? null : expression;
	}

}
