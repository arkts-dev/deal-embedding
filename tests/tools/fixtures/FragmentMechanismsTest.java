import java.util.*;

/** Batch transport is the ordered product of individual mechanisms, not policy. */
public final class FragmentMechanismsTest {
    private static String quote(String value) { return "\""+deal.codegen.jvm.JvmJson.escapeString(value)+"\""; }
    private static String request(String operation,String input) { return "{\"function\":"+quote(operation)+",\"input\":"+quote(input)+"}"; }
    public static void main(String[] args) {
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
        String view="// 😀 preserved\r\nimport * as ui from \"./platform.dealui-pack\";\r\n// @ui-root\r\nexport view Main(): View { ui.Column() {ui.Text(text: \"} 😀\")} }\r\n// trailing comment }";
        String nodes="ui.Text(text: \"Missing generic need\")\n";
        String appended=FragmentMechanisms.invoke("appendViewNodes","{\"source\":"+quote(view)+",\"nodes\":"+quote(nodes)+"}");
        if(!appended.replace("\n"+nodes,"").equals(view) || !appended.contains(nodes+"} }\r\n// trailing"))throw new AssertionError("Root insertion changed original bytes: "+appended);
        try {FragmentRegions.appendViewNodes("export view Main(): View {}",nodes);throw new AssertionError("Unrooted view accepted");}catch(IllegalArgumentException expected) { }
        System.out.println("syntax transport: batch equals individual results, per-item failure ordering, binding-sensitive memo and invalid syntax passed");
    }
}
