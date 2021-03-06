package org.codehawk.plugin.java.checks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sonar.check.Rule;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.CaseGroupTree;
import org.sonar.plugins.java.api.tree.CatchTree;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.DoWhileStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.ForEachStatement;
import org.sonar.plugins.java.api.tree.ForStatementTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.IfStatementTree;
import org.sonar.plugins.java.api.tree.LabeledStatementTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.SwitchStatementTree;
import org.sonar.plugins.java.api.tree.SynchronizedStatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.TryStatementTree;
import org.sonar.plugins.java.api.tree.VariableTree;
import org.sonar.plugins.java.api.tree.WhileStatementTree;

@Rule(key = "AvoidShotgunSurgery")
public class AvoidShotgunSurgery extends IssuableSubscriptionVisitor {
	private Map<String, Map<String, List<String>>> usedInMethod = new HashMap<String, Map<String, List<String>>>();
	private Map<String, Map<String, List<String>>> usedInVariable = new HashMap<String, Map<String, List<String>>>();
	private Map<String, Map<String, List<String>>> classCount = new HashMap<String, Map<String, List<String>>>();
	private Map<String, Map<String, List<String>>> methodCount = new HashMap<String, Map<String, List<String>>>();
	private Map<String, Map<MethodTree, JavaFileScannerContext>> location = new HashMap<String, Map<MethodTree, JavaFileScannerContext>>();
	private Map<String, List<String>> hasShowed = new HashMap<String, List<String>>();
	private List<List<String>> Inheritance = new ArrayList<List<String>>();
	private List<String> objectList = new ArrayList<String>();
	private List<String> reusedList = new ArrayList<String>();

	@Override
	public List<Tree.Kind> nodesToVisit() {
		return Collections.singletonList(Tree.Kind.CLASS);// check every class
	}

	@Override
	public void visitNode(Tree tree) {
		ClassTree classTree = (ClassTree) tree;
		if (classTree.superClass() != null) {
			checkInheritance(classTree);
		}
		checkInnerClassTree(classTree);
		comparedWithUsedInMethod();
		comparedWithUsedInVariable();
		showSmell();
	}
	
	private void checkInheritance(ClassTree classTree) {
		List<String> innerInherit = new ArrayList<String>();
		if (!checkIsNewInheritance(classTree.simpleName().name(),classTree.superClass().symbolType().fullyQualifiedName())) {
			innerInherit.add(classTree.superClass().symbolType().fullyQualifiedName());
			innerInherit.add(classTree.simpleName().name());
		}
		Inheritance.add(innerInherit);
	}
	
	private Boolean checkIsNewInheritance(String thisClass, String superClass) {
		for (int i = 0; i < Inheritance.size(); i++) {
			if (Inheritance.get(i).indexOf(thisClass) != -1 && Inheritance.get(i).indexOf(superClass) == -1) {
				Inheritance.get(i).add(superClass);
				return true;
			} else if (Inheritance.get(i).indexOf(superClass) != -1 && Inheritance.get(i).indexOf(thisClass) == -1) {
				Inheritance.get(i).add(thisClass);
				return true;
			}
		}
		return false;
	}
	
	private void checkInnerClassTree(ClassTree classTree) {
		Map<String, List<String>> innerUsedInMethod = new HashMap<String, List<String>>();
		Map<String, List<String>> innerUsedInVariable = new HashMap<String, List<String>>();
		Map<String, List<String>> innerClassCount = new HashMap<String, List<String>>();
		Map<String, List<String>> innerMethodCount = new HashMap<String, List<String>>();
		Map<MethodTree, JavaFileScannerContext> innerlocation = new HashMap<MethodTree, JavaFileScannerContext>();
		List<Tree> treeList = classTree.members();
		for (Tree tempTree : treeList) {// check every method (constructor) in each class
			if (tempTree.is(Tree.Kind.METHOD) || tempTree.is(Tree.Kind.CONSTRUCTOR)) {
				MethodTree methodTree = (MethodTree) tempTree;
				innerClassCount.put(methodTree.simpleName().name(), new ArrayList<String>());
				innerMethodCount.put(methodTree.simpleName().name(), new ArrayList<String>());
				innerUsedInMethod.put(methodTree.simpleName().name(), new ArrayList<String>());
				BlockTree blockTree = methodTree.block();
				List<StatementTree> statementTreeList = blockTree.body();
				checkStatementTree(statementTreeList);
				innerUsedInMethod.get(methodTree.simpleName().name()).addAll(reusedList);
				innerlocation.put(methodTree, context);
			} else if (tempTree.is(Tree.Kind.VARIABLE)) {// check every variable in each class
				VariableTree variableTree = (VariableTree) tempTree;
				innerUsedInVariable.put(variableTree.simpleName().name(), new ArrayList<String>());
				if (variableTree.initializer().is(Tree.Kind.NEW_CLASS)) {
					objectList.add(variableTree.simpleName().name());
					objectList.add(((IdentifierTree) variableTree.type()).name());
				} else {
					checkExpressionTree(variableTree.initializer());
				}
				innerUsedInVariable.get(variableTree.simpleName().name()).addAll(reusedList);
			}
			reusedList.clear();
		}
		methodCount.put(classTree.simpleName().name(), innerMethodCount);
		classCount.put(classTree.simpleName().name(), innerClassCount);
		usedInMethod.put(classTree.simpleName().name(), innerUsedInMethod);
		usedInVariable.put(classTree.simpleName().name(), innerUsedInVariable);
		location.put(classTree.simpleName().name(), innerlocation);
		objectList.clear();
	}

	private void checkStatementTree(List<StatementTree> list) {
		for (StatementTree statementTree : list) {
			switch (statementTree.kind()) {

			case METHOD_INVOCATION:
				MethodInvocationTree methodInvocationTree = (MethodInvocationTree) statementTree;
				ExpressionTree innerExpressionTree = methodInvocationTree.methodSelect();
				if (innerExpressionTree.is(Tree.Kind.MEMBER_SELECT)) {
					MemberSelectExpressionTree memberSelectExpressionTree = (MemberSelectExpressionTree) innerExpressionTree;
					if (memberSelectExpressionTree.expression().is(Tree.Kind.IDENTIFIER)) {
						IdentifierTree frontIdentifierTree = (IdentifierTree) memberSelectExpressionTree.expression();
						reusedList.add(checkIsObject(frontIdentifierTree.name()));
						reusedList.add(memberSelectExpressionTree.identifier().name());
					}
					checkExpressionTree(memberSelectExpressionTree.expression());
				}
				break;

			case VARIABLE:
				VariableTree variableTree = (VariableTree) statementTree;
				if (variableTree.initializer().is(Tree.Kind.NEW_CLASS)) {
					objectList.add(variableTree.simpleName().name());
					objectList.add(((IdentifierTree) variableTree.type()).name());
				} else {
					checkExpressionTree(variableTree.initializer());
				}
				break;

			case EXPRESSION_STATEMENT:
				ExpressionStatementTree expressionStatementTree = (ExpressionStatementTree) statementTree;
				checkExpressionTree(expressionStatementTree.expression());
				break;

			case SWITCH_STATEMENT:// get switch statement in this method
				SwitchStatementTree switchStatementTree = (SwitchStatementTree) statementTree;
				checkExpressionTree(switchStatementTree.expression());
				List<CaseGroupTree> caseGroupTreeList = switchStatementTree.cases();
				for (CaseGroupTree caseGroupTree : caseGroupTreeList) {
					List<StatementTree> statementTreeList = caseGroupTree.body();
					checkStatementTree(statementTreeList);
				}
				break;

			case IF_STATEMENT:// get if statement in this method
				IfStatementTree ifStatementTree = (IfStatementTree) statementTree;
				checkExpressionTree(ifStatementTree.condition());
				checkInnerStatementTree(ifStatementTree.thenStatement());// check if part in this if statement
				checkElseIfStatementTree(ifStatementTree);// check elseif and else parts in this if statement
				break;

			case WHILE_STATEMENT:// get while statement in this method
				WhileStatementTree whileStatementTree = (WhileStatementTree) statementTree;
				checkExpressionTree(whileStatementTree.condition());
				checkInnerStatementTree(whileStatementTree.statement());
				break;

			case FOR_STATEMENT:// get for statement in this method
				ForStatementTree forStatementTree = (ForStatementTree) statementTree;
				checkExpressionTree(forStatementTree.condition());
				checkInnerStatementTree(forStatementTree.statement());
				break;

			case DO_STATEMENT:// get do_while statement in this method
				DoWhileStatementTree doWhileStatementTree = (DoWhileStatementTree) statementTree;
				checkExpressionTree(doWhileStatementTree.condition());
				checkInnerStatementTree(doWhileStatementTree.statement());
				break;

			case FOR_EACH_STATEMENT:// get for_each statement in this method
				ForEachStatement forEachStatement = (ForEachStatement) statementTree;
				checkExpressionTree(forEachStatement.expression());
				checkInnerStatementTree(forEachStatement.statement());
				break;

			case LABELED_STATEMENT:// get labeled statement in this method
				LabeledStatementTree labeledStatementTree = (LabeledStatementTree) statementTree;
				List<StatementTree> labeledStatementTreeList = new ArrayList<>();
				labeledStatementTreeList.add(labeledStatementTree.statement());
				checkStatementTree(labeledStatementTreeList);
				break;

			case SYNCHRONIZED_STATEMENT:// get synchronized statement in this method
				SynchronizedStatementTree synchronizedStatementTree = (SynchronizedStatementTree) statementTree;
				checkExpressionTree(synchronizedStatementTree.expression());
				checkInnerBlockTree(synchronizedStatementTree.block());
				break;

			case TRY_STATEMENT:// get try_catch statement in this method
				TryStatementTree tryStatementTree = (TryStatementTree) statementTree;// check try part in this try
																						// statement
				if (tryStatementTree.tryKeyword() != null) {
					checkInnerBlockTree(tryStatementTree.block());
				}
				List<CatchTree> catchTreeList = tryStatementTree.catches();// check catch part in this try statement
				for (CatchTree catchtree : catchTreeList) {
					checkInnerBlockTree(catchtree.block());
				}
				if (tryStatementTree.finallyKeyword() != null) {
					checkInnerBlockTree(tryStatementTree.finallyBlock());// check finally part in this try statement
				}
				break;
				
			default:
				break;
			}
		}
	}

	private void checkExpressionTree(ExpressionTree expressionTree) {
		if (expressionTree.is(Tree.Kind.METHOD_INVOCATION)) {
			MethodInvocationTree methodInvocationTree = (MethodInvocationTree) expressionTree;
			ExpressionTree innerExpressionTree = methodInvocationTree.methodSelect();
			if (innerExpressionTree.is(Tree.Kind.MEMBER_SELECT)) {
				MemberSelectExpressionTree memberSelectExpressionTree = (MemberSelectExpressionTree) innerExpressionTree;
				if (memberSelectExpressionTree.expression().is(Tree.Kind.IDENTIFIER)) {
					IdentifierTree frontIdentifierTree = (IdentifierTree) memberSelectExpressionTree.expression();
					reusedList.add(checkIsObject(frontIdentifierTree.name()));
					reusedList.add(memberSelectExpressionTree.identifier().name());
				}
				checkExpressionTree(memberSelectExpressionTree.expression());
			}
		} else if (expressionTree.is(Tree.Kind.MEMBER_SELECT)) {
			MemberSelectExpressionTree memberSelectExpressionTree = (MemberSelectExpressionTree) expressionTree;
			checkExpressionTree(memberSelectExpressionTree.expression());
		} else if (expressionTree.is(Tree.Kind.ASSIGNMENT) || expressionTree.is(Tree.Kind.AND_ASSIGNMENT)
				|| expressionTree.is(Tree.Kind.DIVIDE_ASSIGNMENT) || expressionTree.is(Tree.Kind.LEFT_SHIFT_ASSIGNMENT)
				|| expressionTree.is(Tree.Kind.MINUS_ASSIGNMENT) || expressionTree.is(Tree.Kind.MULTIPLY_ASSIGNMENT)
				|| expressionTree.is(Tree.Kind.OR_ASSIGNMENT) || expressionTree.is(Tree.Kind.PLUS_ASSIGNMENT)
				|| expressionTree.is(Tree.Kind.REMAINDER_ASSIGNMENT)
				|| expressionTree.is(Tree.Kind.RIGHT_SHIFT_ASSIGNMENT)
				|| expressionTree.is(Tree.Kind.UNSIGNED_RIGHT_SHIFT_ASSIGNMENT)
				|| expressionTree.is(Tree.Kind.XOR_ASSIGNMENT)) {
			AssignmentExpressionTree assignmentExpressionTree = (AssignmentExpressionTree) expressionTree;
			checkExpressionTree(assignmentExpressionTree.variable());
			checkExpressionTree(assignmentExpressionTree.expression());
		} else if (expressionTree.is(Tree.Kind.EQUAL_TO) || expressionTree.is(Tree.Kind.GREATER_THAN_OR_EQUAL_TO)
				|| expressionTree.is(Tree.Kind.LESS_THAN_OR_EQUAL_TO) || expressionTree.is(Tree.Kind.NOT_EQUAL_TO)
				|| expressionTree.is(Tree.Kind.AND) || expressionTree.is(Tree.Kind.CONDITIONAL_AND)
				|| expressionTree.is(Tree.Kind.CONDITIONAL_OR) || expressionTree.is(Tree.Kind.DIVIDE)
				|| expressionTree.is(Tree.Kind.GREATER_THAN) || expressionTree.is(Tree.Kind.LEFT_SHIFT)
				|| expressionTree.is(Tree.Kind.LESS_THAN) || expressionTree.is(Tree.Kind.MINUS)
				|| expressionTree.is(Tree.Kind.MULTIPLY) || expressionTree.is(Tree.Kind.OR)
				|| expressionTree.is(Tree.Kind.PLUS) || expressionTree.is(Tree.Kind.REMAINDER)
				|| expressionTree.is(Tree.Kind.RIGHT_SHIFT) || expressionTree.is(Tree.Kind.UNSIGNED_RIGHT_SHIFT)
				|| expressionTree.is(Tree.Kind.XOR)) {
			BinaryExpressionTree binaryExpressionTree = (BinaryExpressionTree) expressionTree;
			checkExpressionTree(binaryExpressionTree.leftOperand());
			checkExpressionTree(binaryExpressionTree.rightOperand());
		}
	}

	private void checkElseIfStatementTree(IfStatementTree ifStatementTree) {// check elseif and else parts in if statement method
		if (ifStatementTree.elseKeyword() != null) {
			StatementTree elseStatementTree = ifStatementTree.elseStatement();
			if (elseStatementTree.is(Tree.Kind.IF_STATEMENT)) {
				IfStatementTree elseifStatementTree = (IfStatementTree) elseStatementTree;
				checkElseIfStatementTree(elseifStatementTree);// check next part is elseif or else statement
				checkExpressionTree(elseifStatementTree.condition());
				checkInnerStatementTree(elseifStatementTree.thenStatement());// check elseif part in if statement
			} else {
				checkInnerStatementTree(elseStatementTree);// check else part in if statement
			}
		}
	}

	private void checkInnerStatementTree(StatementTree statementTree) {// check inner statement method
		if (statementTree.is(Tree.Kind.BLOCK)) {
			BlockTree blockTree = (BlockTree) statementTree;
			List<StatementTree> statementTreeList = blockTree.body();
			checkStatementTree(statementTreeList);
		}
	}

	private void checkInnerBlockTree(BlockTree blockTree) {// check inner block method
		List<StatementTree> statementTreeList = blockTree.body();
		checkStatementTree(statementTreeList);
	}

	private String checkIsObject(String name) {
		for (int i = 0; i + 1 < objectList.size(); i += 2) {
			if (name.equals(objectList.get(i))) {
				return objectList.get(i + 1);
			}
		}
		return name;
	}

	private void comparedWithUsedInMethod() {
		for (String className : usedInMethod.keySet()) {
			for (String methodName : usedInMethod.get(className).keySet()) {
				for (int i = 0; i < usedInMethod.get(className).get(methodName).size(); i += 2) {
					String tempClassName = usedInMethod.get(className).get(methodName).get(i);
					String tempMethodName = usedInMethod.get(className).get(methodName).get(i + 1);
					if(classCount.containsKey(tempClassName) && classCount.get(tempClassName).containsKey(tempMethodName)) {
						if (classCount.get(tempClassName).get(tempMethodName).indexOf(className) == -1 && noInheritance(className, tempClassName)) {
							classCount.get(tempClassName).get(tempMethodName).add(className);
						}
					}
					if(methodCount.containsKey(tempClassName) && methodCount.get(tempClassName).containsKey(tempMethodName)) {
						if (methodCount.get(tempClassName).get(tempMethodName).indexOf(className + methodName) == -1 && noInheritance(className, tempClassName)) {
							methodCount.get(tempClassName).get(tempMethodName).add(className + methodName);
						}
					}
				}
			}
		}
	}
	
	private void comparedWithUsedInVariable() {
		for (String className : usedInVariable.keySet()) {
			for (String variableName : usedInVariable.get(className).keySet()) {
				for (int i = 0; i < usedInVariable.get(className).get(variableName).size(); i += 2) {
					String tempClassName = usedInVariable.get(className).get(variableName).get(i);
					String tempMethodName = usedInVariable.get(className).get(variableName).get(i + 1);
					if(classCount.containsKey(tempClassName) && classCount.get(tempClassName).containsKey(tempMethodName)) {
						if (classCount.get(tempClassName).get(tempMethodName).indexOf(className) == -1 && noInheritance(className, tempClassName)) {
							classCount.get(tempClassName).get(tempMethodName).add(className);
						}
					}
				}
			}
		}
	}

	private Boolean noInheritance(String thisClass, String superClass) {
		for (int i = 0; i < Inheritance.size(); i++) {
			if (Inheritance.get(i).indexOf(thisClass) != -1 && Inheritance.get(i).indexOf(superClass) != -1) {
				return false;
			}
		}
		return true;
	}

	private void showSmell() {
		for (String className : methodCount.keySet()) {
			for (String methodName : methodCount.get(className).keySet()) {
				for (String className2 : location.keySet()) {
					for (MethodTree methodtree : location.get(className2).keySet()) {
						String methodName2 = methodtree.simpleName().name();
						if (methodCount.get(className).get(methodName).size() > 7 && classCount.get(className).get(methodName).size() > 10) {
							if (className.equals(className2) && methodName.equals(methodName2)) {
								if (!hasShowed.containsKey(className)) {
									location.get(className2).get(methodtree).addIssue(methodtree.openParenToken().line(),this, "Code smell \"Shotgun Surgery\" occurred in method \"" + methodName + "\" !");
									hasShowed.put(className, new ArrayList<String>());
									hasShowed.get(className).add(methodName);
								} else if (hasShowed.get(className).indexOf(methodName) == -1) {
									location.get(className2).get(methodtree).addIssue(methodtree.openParenToken().line(),this, "Code smell \"Shotgun Surgery\" occurred in method \"" + methodName + "\" !");
									hasShowed.get(className).add(methodName);
								}
							}
						}
					}
				}
			}
		}
	}
}
