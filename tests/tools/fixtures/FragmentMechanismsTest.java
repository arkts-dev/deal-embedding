import java.util.*;

/** Batch transport is the ordered product of individual mechanisms, not policy. */
public final class FragmentMechanismsTest {
    private static String quote(String value) { return "\""+deal.codegen.jvm.JvmJson.escapeString(value)+"\""; }
    private static String request(String operation,String input) { return "{\"function\":"+quote(operation)+",\"input\":"+quote(input)+"}"; }
    public static void main(String[] args) throws Exception {
        // Lexer-only failures must retain their actual diagnostic, including its source span.
        for(String malformed:List.of("// 😀\r\nlet x = @;", "let x = \"\\q\";")) {
            var lexerDiagnostics=new deal.lexer.Lexer(malformed,"fragment.deal").tokenize().diagnostics();
            if(lexerDiagnostics.isEmpty())throw new AssertionError("Fixture has no lexer diagnostic");
            try { FragmentSyntax.parse(malformed);throw new AssertionError("Malformed input admitted"); }
            catch(IllegalArgumentException failure) {
                for(var diagnostic:lexerDiagnostics) {
                    if(!failure.getMessage().contains(diagnostic.toString()))throw new AssertionError("Lexer evidence lost: "+failure.getMessage());
                    var excerpt=deal.diagnostics.DiagnosticExcerpt.fromSource("fragment.deal",malformed,diagnostic.range());
                    if(excerpt!=null && (!failure.getMessage().contains("SOURCE fragment.deal UTF-16 ["+excerpt.startScalarOffset()+","+excerpt.endScalarOffset()+")\n"+excerpt.text())))
                        throw new AssertionError("Exact source excerpt lost: "+failure.getMessage());
                }
            }
            String response=FragmentMechanisms.invoke("batch","{\"requests\":["+request("analyzeFunctions","{\"source\":"+quote(malformed)+"}")+"]}");
            if(response.contains("Invalid fragment syntax: []")||!response.contains("FRAGMENT_SYNTAX"))throw new AssertionError(response);
        }
        String repeated="import * as session from \"embedding/session\";\n";
        var analysed=FragmentAnalysis.functions(repeated+repeated+"export function probe(state: AppState): number { return state.ns.toSorted((a: number,b: number): number => a-b).length; }",Map.of("ns","number[]"));
        if(!analysed.toString().contains("intrinsics=[state.ns.toSorted]"))throw new AssertionError("Repeated composition import lost compiler facts: "+analysed);
        String valid="{\"source\":\"state.value + 1\",\"expected\":\"number\",\"names\":{},\"fields\":{\"value\":\"f_value\"},\"types\":{\"value\":\"number\"},\"variables\":{\"state\":\"AppState\"}}";
        String invalid=valid.replace("state.value + 1","(");
        String batch="{\"requests\":["+request("expression",valid)+","+request("expression",invalid)+","+request("analyzeExpression","{\"source\":\"true\"}")+"]}";
        var session=new FragmentMechanisms.Session();
        String direct=FragmentMechanisms.invoke("expression",valid);
        if(!direct.equals(session.invoke("expression",valid)) || !direct.equals(session.invoke("expression",valid)))throw new AssertionError("Memo changed result");
        String other=valid.replace("f_value","f_other");
        if(session.invoke("expression",other).equals(direct))throw new AssertionError("Bindings omitted from memo key");
        String output=session.invoke("batch",batch);
        if(!output.equals(FragmentMechanisms.invoke("batch",batch)))throw new AssertionError("Batch/cache mismatch");
        if(!output.contains(quote(direct)) || !output.contains("FRAGMENT_SYNTAX") || !output.contains("\\\"reads\\\""))throw new AssertionError("Missing success/failure/continuation results: "+output);
        for(int i=0;i<2;i++)try { session.invoke("expression",invalid);throw new AssertionError("Invalid syntax admitted"); }catch(IllegalArgumentException expected) { }
        try { session.invoke("batch","{\"requests\":["+request("batch","{}")+"]}");throw new AssertionError("Recursive batch admitted"); }catch(IllegalArgumentException expected) { }
        // The line transport a long-lived harness uses: one call per line, the same results as one
        // process per call, a failure answered on its own line and no result retained between calls.
        // It runs through the real process entries so what the harness consumes is what is tested.
        String first=FragmentMechanisms.invoke("expression",valid),second=FragmentMechanisms.invoke("expression",other);
        Process oneShot=new ProcessBuilder("java","-cp",System.getProperty("java.class.path"),"FragmentMechanisms","expression").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try(java.io.Writer requests=new java.io.OutputStreamWriter(oneShot.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8)) { requests.write(valid); }
        String oneShotResult=new String(oneShot.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        if(oneShot.waitFor()!=0)throw new AssertionError("One-shot process entry exited "+oneShot.exitValue());
        if(!oneShotResult.equals(first))throw new AssertionError("One-shot process entry changed the result: "+oneShotResult);
        String view="// 😀 preserved\r\nimport * as ui from \"./platform.dealui-pack\";\r\n// @ui-root\r\nexport view Main(): View { ui.Column() {ui.Text(text: \"} 😀\")} }\r\n// trailing comment }";
        String nodes="ui.Text(text: \"Missing generic need\")\n";
        String appended=FragmentMechanisms.invoke("appendViewNodes","{\"source\":"+quote(view)+",\"nodes\":"+quote(nodes)+"}");
        Process transport=new ProcessBuilder("java","-cp",System.getProperty("java.class.path"),"FragmentMechanisms","serve").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try(java.io.Writer requests=new java.io.OutputStreamWriter(transport.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8)) {
            requests.write(request("expression",valid)+"\n"+request("expression",invalid)+"\n"+request("expression",valid)+"\n"+request("appendViewNodes","{\"source\":"+quote(view)+",\"nodes\":"+quote(nodes)+"}")+"\n"+request("expression",other)+"\n");
        }
        byte[] streamed=transport.getInputStream().readAllBytes();
        if(transport.waitFor()!=0)throw new AssertionError("Line transport exited "+transport.exitValue());
        String wire=new String(streamed,java.nio.charset.StandardCharsets.UTF_8);
        if(!wire.endsWith("\n"))throw new AssertionError("Line transport replied without its line terminator: "+wire);
        String[] answered=wire.split("\n",-1);
        if(answered.length!=6 || !answered[5].isEmpty())throw new AssertionError("Line transport answered "+(answered.length-1)+" of 5 requests: "+wire);
        if(!answered[0].equals("{\"ok\":true,\"value\":"+quote(first)+"}"))throw new AssertionError("Line transport changed the result: "+answered[0]);
        if(!answered[1].startsWith("{\"ok\":false,\"error\":\"")||answered[1].contains("\"value\""))throw new AssertionError("Line transport admitted invalid syntax: "+answered[1]);
        if(!answered[2].equals(answered[0]))throw new AssertionError("Line transport did not repeat an unchanged result: "+answered[2]);
        // One reply must stay one line even when the result carries CR/LF of its own.
        if(!answered[3].equals("{\"ok\":true,\"value\":"+quote(appended)+"}"))throw new AssertionError("Line transport framed a multiline result wrongly: "+answered[3]);
        if(!answered[4].equals("{\"ok\":true,\"value\":"+quote(second)+"}"))throw new AssertionError("Line transport lost the last result: "+answered[4]);
        if(!appended.replace("\n"+nodes,"").equals(view) || !appended.contains(nodes+"} }\r\n// trailing"))throw new AssertionError("Root insertion changed original bytes: "+appended);
        try {FragmentRegions.appendViewNodes("export view Main(): View {}",nodes);throw new AssertionError("Unrooted view accepted");}catch(IllegalArgumentException expected) { }
        System.out.println("syntax transport: batch equals individual results, per-item failure ordering, binding-sensitive memo, both process entries and multiline line framing passed");
    }
}
