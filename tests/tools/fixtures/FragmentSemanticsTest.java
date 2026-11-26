import java.nio.file.*;
import java.util.*;

/** Emit original and both production rewrites for differential compiler/runtime checks. */
public final class FragmentSemanticsTest {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        String templates="""
export function run(): string {
  let render: (value: number) => string = (value: number): string => `value ${value} \\ ${"`"}`;
  let sink: (value: number) => void = (value: number): void => noop(value);
  sink(7);
  let nested: string = `outer ${`inner ${3}`} 😀`;
  let empty: string = ``;
  return render(4) + ":" + nested + ":" + empty;
}
""";
        String updates="""
export function run(): string {
  let n: number = 4;
  n += 3; n -= 1; n *= 2; n /= 3; n %= 3;
  let old: number = n++;
  let current: number = ++n;
  let down: number = n--;
  let preDown: number = --n;
  let rows: number[] = [10,20]; let index: number = 0;
  let previous: number = rows[index++]++;
  rows[index++] /* keep one target evaluation */ += 4;
  let apply: (state: AppState) => number = (state): number => state.value;
  let state: AppState = {value:9};
  let render: (value: number) => string = function(value): string { return `value ${value}`; };
  return `😀 ${n}:${old}:${current}:${down}:${preDown}:${previous}:${index}:${rows[0]}:${rows[1]}:${apply(state)}:${render(4)}`;
}
""";
        String inferred="""
import * as append from "./append";
export function opaque(value: unknown): unknown { return value; }
export function run(): string {
  let value: string | null = "stable";
  if (value === null) { return "missing"; }
  let visits: number = 0;
  for (let index of [1,2]) { if (value === "stable") { visits = visits+1; } }
  while (visits < 3) { let checked: string = value; visits = visits+1; }
  let data: Record<string, unknown> = {nested:{label:value},empty:[],mixed:[1,"two",null],rows:[{id:"row"}]};
  let empty: unknown[] = data.empty as unknown[]; empty.push(false);
  let mixed: unknown[] = data.mixed as unknown[];
  let rows: Record<string, unknown>[] = data.rows as Record<string, unknown>[];
  let numbers: number[] = [1]; let length: number = append.add(numbers);
  let erased: unknown = opaque(numbers); let rejected: boolean = false;
  try { let invalid: unknown[] = erased as unknown[]; } catch (error) { rejected = true; }
  return `${visits}:${empty.length}:${mixed.length}:${rows[0].id as string}:${length}:${rejected ? "true" : "false"}`;
}
""";
        Files.writeString(output.resolve("append.deal"),"export function add(values: number[]): number { return values.push(2); }\n");
        emit(output,"templates",templates);
        emit(output,"updates",updates);
        emit(output,"inferred",inferred);
    }
    private static void emit(Path output,String name,String source) throws Exception {
        source+="\nexport function noop(value: number): void { return; }\nexport function main(): void { return; }\n";
        String declarations=name.equals("updates")?"export class AppState { value: number; }\n":"";
        String view="import * as app from \"./experience\"; import * as ui from \"./platform.dealui-pack\"; export view Panel(state: app.AppState): View { ui.Text(text:\"probe\") }";
        Map<String,String> fields=Map.of("value","qualified_value");
        var qualified=FragmentQualifier.qualify("",source,view,Map.of(),fields,Map.of("value","number"));
        // AppState is composer-owned; qualify only its issued fields in the test declaration.
        Files.writeString(output.resolve(name+"_original.deal"),declarations+source);
        Files.writeString(output.resolve(name+"_syntax.deal"),FragmentSyntax.qualify(declarations+source,Map.of()));
        Files.writeString(output.resolve(name+"_qualified.deal"),(name.equals("inferred")?"import * as append from \"./append\";\n":"")+declarations.replace("value: number","qualified_value: number")+qualified.get("helpers"));
        if(name.equals("updates")) {
            String expression=FragmentQualifier.expression("(state): number => state.value",Map.of(),fields,Map.of("value","number"),"(state: AppState) => number",Map.of());
            if(!expression.contains("state.qualified_value"))throw new AssertionError(expression);
            String bound=FragmentSyntax.function("export function bind(): number { return slot; }","bind",Map.of(),Map.of("slot","rows[index++] += 4"));
            if(!bound.contains("++")||!bound.contains("+="))throw new AssertionError(bound);
            String function=FragmentSyntax.function(source,"run",Map.of(),Map.of());
            if(!function.contains("+=")||!function.contains("++"))throw new AssertionError(function);
            String body=FragmentSyntax.body("function run(): number { slot(); return 0; }","run",Map.of(),Map.of(),Map.of("slot","let n:number=1; n += 3; n++;"));
            if(!body.contains("+=")||!body.contains("++"))throw new AssertionError(body);
        }
    }
}
