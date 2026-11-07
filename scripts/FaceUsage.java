import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** Parse Java syntax without loading Minecraft: trace string values reaching face setters. */
class FaceUsage {
    record Method(MethodTree tree, String file, Map<String,List<ExpressionTree>> values) {}
    record Call(MethodInvocationTree tree, Method owner) {}
    static final List<Method> methods = new ArrayList<>();
    static final List<Call> calls = new ArrayList<>();
    static final Map<String,List<ExpressionTree>> fields = new HashMap<>();
    static final Map<String,Set<String>> emitted = new TreeMap<>();
    static String name(MethodInvocationTree call) {
        var select = call.getMethodSelect();
        return select instanceof MemberSelectTree m ? m.getIdentifier().toString() : select.toString();
    }
    static void bind(Map<String,List<ExpressionTree>> values,String key,ExpressionTree value) {
        if(value!=null) values.computeIfAbsent(key,k->new ArrayList<>()).add(value);
    }
    static Set<String> values(Tree tree,Method owner,Set<Tree> seen) {
        var result = new TreeSet<String>();
        if(tree==null || !seen.add(tree)) return result;
        if(tree instanceof LiteralTree literal && literal.getValue() instanceof String s) {
            if(!s.isEmpty()) result.add(s);
        } else if(tree instanceof ParenthesizedTree p) result.addAll(values(p.getExpression(),owner,seen));
        else if(tree instanceof ConditionalExpressionTree c) {
            result.addAll(values(c.getTrueExpression(),owner,seen));
            result.addAll(values(c.getFalseExpression(),owner,seen));
        } else if(tree instanceof SwitchExpressionTree s) {
            for(var c:s.getCases()) {
                if(c.getBody() instanceof ExpressionTree e) result.addAll(values(e,owner,seen));
                else new TreeScanner<Void,Void>() {
                    @Override public Void visitYield(YieldTree y,Void unused) {
                        result.addAll(values(y.getValue(),owner,seen));return null;
                    }
                }.scan(c,null);
            }
        } else if(tree instanceof IdentifierTree || tree instanceof MemberSelectTree) {
            String key = tree instanceof IdentifierTree i ? i.getName().toString() : ((MemberSelectTree)tree).getIdentifier().toString();
            for(var e:owner.values.getOrDefault(key,List.of())) result.addAll(values(e,owner,seen));
            for(var e:fields.getOrDefault(key,List.of())) result.addAll(values(e,owner,seen));
            var params=owner.tree.getParameters();
            for(int i=0;i<params.size();i++) if(params.get(i).getName().contentEquals(key)) {
                for(var call:calls) if(name(call.tree).equals(owner.tree.getName().toString()) && call.tree.getArguments().size()==params.size())
                    result.addAll(values(call.tree.getArguments().get(i),call.owner,seen));
            }
        } else if(tree instanceof MethodInvocationTree call) {
            for(var method:methods) if(method.tree.getName().contentEquals(name(call))
                    && method.tree.getParameters().size()==call.getArguments().size()
                    && method.tree.getReturnType()!=null && method.tree.getReturnType().toString().equals("String")) {
                new TreeScanner<Void,Void>() {
                    @Override public Void visitReturn(ReturnTree r,Void unused) {
                        result.addAll(values(r.getExpression(),method,seen));return null;
                    }
                }.scan(method.tree.getBody(),null);
            }
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8);
            var paths=Files.walk(Path.of(args[0]))) {
            var files=paths.filter(p->p.toString().endsWith(".java")).sorted().map(Path::toFile).toList();
            var task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none"),null,manager.getJavaFileObjectsFromFiles(files));
            for(var unit:task.parse()) new TreeScanner<Void,Void>() {
                Method owner;
                @Override public Void visitMethod(MethodTree tree,Void unused) {
                    var previous=owner;
                    owner=new Method(tree,unit.getSourceFile().getName(),new HashMap<>());methods.add(owner);
                    super.visitMethod(tree,unused);owner=previous;return null;
                }
                @Override public Void visitVariable(VariableTree v,Void unused) {
                    bind(owner==null?fields:owner.values,v.getName().toString(),v.getInitializer());
                    return super.visitVariable(v,unused);
                }
                @Override public Void visitAssignment(AssignmentTree a,Void unused) {
                    if(owner!=null)bind(owner.values,a.getVariable().toString().replaceAll("^.*\\.",""),a.getExpression());
                    return super.visitAssignment(a,unused);
                }
                @Override public Void visitMethodInvocation(MethodInvocationTree c,Void unused) {
                    if(owner!=null)calls.add(new Call(c,owner));return super.visitMethodInvocation(c,unused);
                }
            }.scan(unit,null);
            for(var diagnostic:diagnostics.getDiagnostics()) if(diagnostic.getKind()==Diagnostic.Kind.ERROR)
                throw new IllegalArgumentException("Cannot scan Java face emitters: "+diagnostic);
        }
        for(var call:calls) {
            String name=name(call.tree);
            if(Set.of("showReaction","showReactionDelayed","forceReaction","holdReaction").contains(name)
                    || name.equals("expression") && call.tree.getArguments().size()==1) {
                for(var value:values(call.tree.getArguments().getFirst(),call.owner,Collections.newSetFromMap(new IdentityHashMap<>())))
                    emitted.computeIfAbsent(value,k->new TreeSet<>()).add(call.owner.file+":"+call.owner.tree.getName());
            }
        }
        // String-returning face providers include MindLife, EcologyPresentation and client overrides.
        for(var method:methods) if(Set.of("expression","face").contains(method.tree.getName().toString())
                && method.tree.getReturnType()!=null && method.tree.getReturnType().toString().equals("String")) {
            new TreeScanner<Void,Void>() {
                @Override public Void visitReturn(ReturnTree r,Void unused) {
                    for(var value:values(r.getExpression(),method,Collections.newSetFromMap(new IdentityHashMap<>())))
                        emitted.computeIfAbsent(value,k->new TreeSet<>()).add(method.file+":"+method.tree.getName());
                    return null;
                }
            }.scan(method.tree.getBody(),null);
        }
        for(var entry:emitted.entrySet()) System.out.println(entry.getKey()+"\t"+String.join(";",entry.getValue()));
    }
}
