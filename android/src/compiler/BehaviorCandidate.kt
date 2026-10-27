package dev.deal.embedding

import dev.deal.embedding.capabilities.*
import org.json.*

/** A typed, finite operation graph. No source strings are accepted by the maintained route. */
internal class BehaviorCandidate(private val contracts: List<CapabilityContract>, private val allowCustom: Boolean = false) {
    data class Ty(val name: String, val element: Ty? = null) {
        override fun toString() = element?.let { "$it[]" } ?: name
        val scalar get() = element == null && name in setOf("number", "string", "boolean", "null")
    }
    data class V(val type: Ty, val code: String)
    private val number = Ty("number"); private val string = Ty("string"); private val boolean = Ty("boolean")
    private val records = linkedMapOf<String, LinkedHashMap<String, Ty>>()
    private val declarations = StringBuilder(); private val helpers = StringBuilder()
    private val optionalTypes = mutableMapOf<String, Ty>()
    private lateinit var nodes: Map<String, JSONObject>
    private var serial = 0
    private var custom = emptyMap<String,JSONObject>()
    class Rejected(message:String):IllegalArgumentException(message)
    private fun reject(message: String): Nothing = throw Rejected(message)
    private fun array(o: JSONObject, key: String) = o.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    private fun bindings(o: JSONObject, key: String): Map<String,String> = array(o,key).let { entries ->
        if(entries.map{it.getString("field")}.distinct().size!=entries.size) reject("Duplicate binding")
        entries.associate { it.getString("field") to it.getString("node") }
    }
    private fun closed(o: JSONObject, vararg keys: String) { if (o.keys().asSequence().toSet() != keys.toSet()) reject("Expected ${keys.joinToString()}") }
    private fun name(s: String): String {
        if (!s.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) reject("Invalid ID: $s")
        return s
    }
    private fun symbol(s: String) = "m_" + name(s)
    private fun fresh() = "v${serial++}"
    private fun quote(s: String): String {
        if (s.any { it.code < 32 && it !in "\n\r\t" }) reject("Unsupported string control")
        return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\""
    }
    private fun decimal(n: Number): String {
        val d = n.toDouble(); if (!d.isFinite()) reject("Nonfinite number")
        return try { java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString() }
        catch (_:NumberFormatException) { reject("Invalid numeric literal") }
    }
    private fun type(s: String): Ty = if (s.endsWith("[]")) Ty("array", type(s.dropLast(2))) else when(s) {
        "number" -> number; "string" -> string; "boolean" -> boolean; "null" -> Ty("null")
        else -> Ty(symbol(s)).also { if (it.name !in records) reject("Unknown type: $s") }
    }
    private fun field(t: Ty, f: String): Ty = records[t.name]?.get(f) ?: reject("No field $f in $t")
    private fun fields(t: Ty) = records[t.name] ?: reject("Expected record, got $t")
    private fun declare(n: String, fs: LinkedHashMap<String, Ty>): Ty {
        if (n in records) reject("Duplicate record $n")
        records[n] = fs
        declarations.append("export class $n { ").append(fs.entries.joinToString(" ") { "${symbol(it.key)}: ${it.value};" }).append(" }\n")
        return Ty(n)
    }
    private fun copy(t: Ty, source: String, replacements: Map<String, String> = emptyMap()) = fields(t).entries.joinToString(", ", "{", "}") { (f, _) -> "${symbol(f)}: ${replacements[f] ?: "$source.${symbol(f)}"}" }
    private fun initial(t: Ty, raw: Any): String {
        if (t.element != null) {
            val a = raw as? JSONArray ?: reject("Array initializer required")
            return (0 until a.length()).joinToString(",", "[", "]") { initial(t.element, a.get(it)) }
        }
        return when(t.name) {
            "number" -> decimal(raw as? Number ?: reject("Number initializer required"))
            "string" -> quote(raw as? String ?: reject("String initializer required"))
            "boolean" -> (raw as? Boolean ?: reject("Boolean initializer required")).toString()
            "null" -> if(raw === JSONObject.NULL) "null" else reject("Null initializer required")
            else -> {
                val o = raw as? JSONObject ?: reject("Record initializer required")
                if (o.keys().asSequence().toSet() != fields(t).keys) reject("Initializer fields disagree with $t")
                fields(t).entries.joinToString(",", "{", "}") { (f, ft) -> "${symbol(f)}: ${initial(ft,o.get(f))}" }
            }
        }
    }
    private fun default(t: Ty): String = when {
        t.element != null -> "[]"
        t == number -> "0"; t == string -> "\"\""; t == boolean -> "false"; t.name == "null" -> "null"
        else -> fields(t).entries.joinToString(",", "{", "}") { (f, ft) -> "${symbol(f)}: ${default(ft)}" }
    }
    private fun providerType(t: CapabilityType, id: String): Ty = when(t.kind) {
        "number" -> number; "string" -> string; "boolean" -> boolean; "null" -> Ty("null")
        "array" -> Ty("array", providerType(t.element!!,id+"Row"))
        "record" -> {
            val fs = linkedMapOf<String, Ty>()
            t.fields.forEach { (f, ft) ->
                val fieldType = providerType(ft,id+"_"+name(f))
                fs[f] = if (f in t.optionalFields) {
                    val option = declare(id+"_"+name(f)+"Option", linkedMapOf("present" to boolean, "value" to fieldType))
                    optionalTypes[option.name] = fieldType
                    option
                } else fieldType
            }
            declare(id,fs)
        }
        else -> reject("Unsupported provider value ${t.kind}")
    }
    private fun wireType(t: Ty): String = if(t.element != null) wireType(t.element)+"[]" else if(t.scalar)t.name else "table"
    private fun decode(t: Ty, source: String, b: StringBuilder): V {
        if (t.scalar) { val v=fresh(); b.append("let $v: $t = $source;\n"); return V(t,v) }
        if(t.element != null) {
            val raw=fresh();val dest=fresh();val i=fresh()
            b.append("let $raw: ${wireType(t)} = $source;\nlet $dest: $t = [];\nlet $i: number = 0;\nwhile ($i < $raw.length) {\n")
            val value=decode(t.element,"$raw[$i]",b);b.append("$dest[$dest.length] = ${value.code};\n$i = $i + 1;\n}\n")
            return V(t,dest)
        }
        val raw=fresh();b.append("let $raw: table = $source;\n");val vals=linkedMapOf<String,String>()
        fields(t).forEach { (f,ft) ->
            val innerType = optionalTypes[ft.name]
            if (innerType == null) vals[f] = decode(ft,"$raw[${quote(f)}]",b).code
            else {
                val dest = fresh(); val key = fresh()
                b.append("let $dest: $ft = ${default(ft)};\nfor (let $key: string of tables.keys($raw)) { if ($key === ${quote(f)}) {\n")
                val decoded = decode(innerType,"$raw[${quote(f)}]",b)
                b.append("$dest = {m_present:true,m_value:${decoded.code}};\n} }\n")
                vals[f] = dest
            }
        }
        val dest=fresh();b.append("let $dest: $t = ${copy(t,raw,vals)};\n");return V(t,dest)
    }
    private fun encodeArgument(value: V, expected: CapabilityType, b: StringBuilder): String {
        if(expected.kind == "array") {
            val rowType=value.type.element ?: reject("Capability requires array")
            val result=fresh(); val row=fresh()
            fun wire(t:CapabilityType):String=if(t.kind=="array") wire(t.element!!)+"[]" else if(t.kind=="record")"table" else t.kind
            b.append("let $result: ${wire(expected)} = []; for (let $row: $rowType of ${value.code}) {\n")
            val encoded=encodeArgument(V(rowType,row),expected.element!!,b)
            b.append("$result[$result.length] = $encoded;\n}\n")
            return result
        }
        if(expected.kind == "record") {
            if(fields(value.type).keys != expected.fields.keys) reject("Capability record argument fields")
            val result=fresh(); b.append("let $result: table = {};\n")
            expected.fields.forEach { (f,t) ->
                val ft=field(value.type,f)
                if(f in expected.optionalFields) {
                    val inner=optionalTypes[ft.name] ?: reject("Optional argument needs a presence/value record")
                    b.append("if (${value.code}.${symbol(f)}.m_present) {\n")
                    val encoded=encodeArgument(V(inner,"${value.code}.${symbol(f)}.m_value"),t,b)
                    b.append("$result[${quote(f)}] = $encoded;\n}\n")
                } else {
                    val encoded=encodeArgument(V(ft,"${value.code}.${symbol(f)}"),t,b)
                    b.append("$result[${quote(f)}] = $encoded;\n")
                }
            }
            return result
        }
        if(value.type.element!=null || value.type.name!=expected.kind) reject("Capability argument type: expected ${expected.kind}, got ${value.type}")
        return value.code
    }
    private fun requireType(v: V, t: Ty): V { if(v.type!=t)reject("Expected $t, got ${v.type}");return v }
    private fun ref(path: String, env: Map<String,V>): V {
        val parts=path.removePrefix("$").split('.')
        var v=env[parts[0]] ?: reject("Unknown reference $path")
        for(f in parts.drop(1))v=V(field(v.type,f),v.code+"."+symbol(f))
        return v
    }
    /** Emit an expression with lexical row/action bindings. Each collection result is fresh. */
    private fun expr(id: String, env: Map<String,V>, b: StringBuilder, active: Set<String> = emptySet()): V {
        if(id.startsWith("$")) return ref(id,env)
        if(id in active)reject("Expression cycle at $id")
        val n=nodes[id] ?: reject("Unknown expression $id");val op=n.getString("op");val args=strings(n.getJSONArray("args"));val p=n.getJSONObject("params");val chain=active+id
        fun arg(i:Int)=expr(args.getOrElse(i){reject("Missing argument to $op")},env,b,chain)
        fun scalar(i:Int,t:Ty)=requireType(arg(i),t).code
        fun emit(t:Ty,code:String):V {val v=fresh();b.append("let $v: $t = $code;\n");return V(t,v)}
        fun numeric(symbol:String)=emit(number,"(${scalar(0,number)} $symbol ${scalar(1,number)})")
        return when(op) {
            "literal" -> {closed(p,"type","value");val t=type(p.getString("type"));emit(t,initial(t,dataValue(p.getString("value"))))}
            "add" -> numeric("+");"subtract" -> numeric("-");"multiply" -> numeric("*");"divide" -> numeric("/")
            "floor","ceil","abs" -> { val x=scalar(0,number);if(op=="abs"){val v=fresh();b.append("let $v: number = $x;\nif ($v < 0) { $v = -$v; }\n");V(number,v)}else emit(number,"math.$op($x)") }
            "eq","ne","lt","le","gt","ge" -> {
                val a=arg(0);val c=arg(1);if(a.type!=c.type||!a.type.scalar)reject("Comparison types")
                if(op !in setOf("eq","ne") && a.type.name !in setOf("number","string"))reject("Ordered comparison needs number or string")
                emit(boolean,"(${a.code} ${mapOf("eq" to "===","ne" to "!==","lt" to "<","le" to "<=","gt" to ">","ge" to ">=").getValue(op)} ${c.code})")
            }
            "and","or" -> emit(boolean,"(${scalar(0,boolean)} ${if(op=="and")"&&" else "||"} ${scalar(1,boolean)})")
            "not" -> emit(boolean,"!${scalar(0,boolean)}")
            "choose" -> {
                val condition=scalar(0,boolean);val yes=StringBuilder();val no=StringBuilder()
                val a=expr(args[1],env,yes,chain);val c=expr(args[2],env,no,chain);if(a.type!=c.type)reject("Conditional types")
                val v=fresh();b.append("let $v: ${a.type} = ${default(a.type)};\nif ($condition) {\n$yes$v = ${a.code};\n} else {\n$no$v = ${c.code};\n}\n");V(a.type,v)
            }
            "concat" -> emit(string,args.map { requireType(expr(it,env,b,chain),string).code }.joinToString(" + ").ifEmpty { "\"\"" })
            "integer","clock" -> emit(string,"values.${if(op=="clock")"clockMinutes" else "decimal"}(${scalar(0,number)})")
            "length" -> {val a=arg(0);if(a.type.element==null)reject("length needs array");emit(number,"${a.code}.length")}
            "record" -> {val t=type(p.getString("type"));val fs=bindings(p,"fields");if(fs.keys!=fields(t).keys)reject("Record fields");val v=fields(t).mapValues { (f,ft)->requireType(expr(fs.getValue(f),env,b,chain),ft).code };emit(t,copy(t,"",v))}
            "array" -> {val t=type(p.getString("element"));emit(Ty("array",t),args.map { requireType(expr(it,env,b,chain),t).code }.joinToString(",","[","]"))}
            "filter","project","sum","any","all","update" -> {
                val src=arg(0);val rowType=src.type.element ?: reject("$op needs array");val row=fresh();val i=fresh();val inner=StringBuilder()
                val body=expr(args[1],env+ ("row" to V(rowType,row)),inner,chain)
                val rt=when(op){"sum"->number;"any","all"->boolean;"project"->Ty("array",body.type);else->src.type}
                if(op in setOf("filter","any","all"))requireType(body,boolean)
                if(op=="sum")requireType(body,number)
                val dest=fresh();b.append("let $dest: $rt = ${if(op=="all")"true" else default(rt)};\nlet $i: number = 0;\nwhile ($i < ${src.code}.length) {\nlet $row: $rowType = ${src.code}[$i];\n$inner")
                when(op){
                    "filter"->b.append("if (${body.code}) { $dest[$dest.length] = $row; }\n")
                    "project"->b.append("$dest[$dest.length] = ${body.code};\n")
                    "sum"->b.append("$dest = $dest + ${body.code};\n")
                    "any"->b.append("$dest = $dest || ${body.code};\n")
                    "all"->b.append("$dest = $dest && ${body.code};\n")
                    "update"->{
                        requireType(body,boolean);val changes=bindings(p,"fields");val changed=StringBuilder();val replacements=changes.mapValues { (f,node) -> requireType(expr(node,env+("row" to V(rowType,row)),changed,chain),field(rowType,f)).code }
                        val item=fresh();b.append("let $item: $rowType = ${copy(rowType,row)};\nif (${body.code}) {\n$changed$item = ${copy(rowType,row,replacements)};\n}\n$dest[$dest.length] = $item;\n")
                    }
                };b.append("$i = $i + 1;\n}\n");V(rt,dest)
            }
            "join" -> {
                val left=arg(0);val right=arg(1)
                val lt=left.type.element ?: reject("join left needs array");val rt=right.type.element ?: reject("join right needs array")
                val l=fresh();val r=fresh();val inner=StringBuilder();val scope=env+mapOf("left" to V(lt,l),"right" to V(rt,r))
                val predicate=requireType(expr(args[2],scope,inner,chain),boolean)
                val projection=StringBuilder();val value=expr(args[3],scope,projection,chain)
                val dest=fresh();val t=Ty("array",value.type)
                b.append("let $dest: $t = []; for (let $l: $lt of ${left.code}) { for (let $r: $rt of ${right.code}) {\n$inner if (${predicate.code}) {\n$projection $dest[$dest.length] = ${value.code};\n} } }\n")
                V(t,dest)
            }
            "first" -> {
                val a=arg(0);val rt=a.type.element ?: reject("first needs array");val v=fresh();b.append("let $v: $rt = ${default(rt)};\nif (${a.code}.length > 0) { $v = ${a.code}[0]; }\n");V(rt,v)
            }
            "lookup" -> {
                val a=arg(0);val key=arg(1);val rt=a.type.element ?: reject("lookup needs array");val f=p.getString("key");requireType(key,field(rt,f))
                val v=fresh();val i=fresh();val row=fresh();b.append("let $v: $rt = ${default(rt)};\nlet $i: number = 0;\nwhile ($i < ${a.code}.length) { let $row: $rt = ${a.code}[$i]; if ($row.${symbol(f)} === ${key.code}) { $v = $row; } $i = $i + 1; }\n");V(rt,v)
            }
            "sort" -> {
                val a=arg(0);val rt=a.type.element ?: reject("sort needs array");val f=p.getString("field");if(field(rt,f).name !in setOf("number","string"))reject("Sort numeric/string field required")
                val dest=fresh();val i=fresh();val j=fresh();val row=fresh();val left=fresh();val right=fresh()
                b.append("let $dest: ${a.type} = []; let $i: number = 0; while ($i < ${a.code}.length) { let $row: $rt = ${a.code}[$i]; $dest[$dest.length] = $row; $i = $i + 1; }\n$i = 0; while ($i < $dest.length) { let $j: number = 1; while ($j < $dest.length - $i) { let $left: $rt = $dest[$j - 1]; let $right: $rt = $dest[$j]; if ($left.${symbol(f)} > $right.${symbol(f)}) { $dest[$j - 1] = $right; $dest[$j] = $left; } $j = $j + 1; } $i = $i + 1; }\n");V(a.type,dest)
            }
            "basket" -> {
                val a=arg(0);val rt=a.type.element ?: reject("basket needs rows");val idField=p.getString("id");val qty=p.getString("quantity");requireType(V(field(rt,idField),""),string);requireType(V(field(rt,qty),""),number)
                val dest=fresh();val i=fresh();val row=fresh();b.append("let $dest: string = \"\"; let $i: number = 0; while ($i < ${a.code}.length) { let $row: $rt = ${a.code}[$i]; if ($row.${symbol(qty)} > 0) { if (math.floor($row.${symbol(qty)}) !== $row.${symbol(qty)}) { throw {code: \"INVALID_INTEGER\", message: \"Invalid basket quantity\"}; } if ($dest !== \"\") { $dest = $dest + \",\"; } $dest = $dest + $row.${symbol(idField)} + \":\" + values.decimal($row.${symbol(qty)}); } $i = $i + 1; }\n");V(string,dest)
            }
            "custom" -> {
                val section=custom[p.getString("section")] ?: reject("Unknown custom section")
                val params=array(section,"parameters")
                if(args.size!=params.size)reject("Custom arity")
                val values=args.mapIndexed { i,a -> requireType(expr(a,env,b,chain),type(params[i].getString("type"))).code }
                emit(type(section.getString("result")),"custom_${name(section.getString("id"))}(${values.joinToString(",")})")
            }
            "allocate" -> allocation(arg(0),requireType(arg(1),number),requireType(arg(2),number),p,env,b,chain)
            "covers" -> coverage(arg(0),arg(1),p,env,b,chain)
            else -> reject("Unsupported operation $op; request an explicit custom section or full-source fallback")
        }
    }
    private fun allocation(src: V,total: V,budget: V,p:JSONObject,env:Map<String,V>,b:StringBuilder,chain:Set<String>):V {
        val rt=src.type.element ?: reject("allocate needs rows");val q=p.getString("quantity");val cost=p.getString("cost");requireType(V(field(rt,q),""),number);requireType(V(field(rt,cost),""),number)
        val quotas=array(p,"quotas");val mins=quotas.map { requireType(expr(it.getString("minimum"),env,b,chain),number) }
        quotas.forEach { requireType(V(field(rt,it.getString("field")),""),boolean) }
        val fn="allocate${serial++}";val result=declare("Allocation${serial++}",linkedMapOf("found" to boolean,"rows" to src.type))
        // Depth-first finite enumeration: stable catalogue order, no unproven infeasibility on a search quota.
        val quotaParams=quotas.indices.joinToString(""){", quota$it: number"}
        val leaf=quotas.indices.joinToString(" && "){"quota$it <= 0"}.ifEmpty{"true"}
        val recArgs=quotas.indices.map { i -> "quota$i - count * flag$i" }.joinToString(""){", $it"}
        val flags=quotas.mapIndexed { i,o -> "let flag$i: number = 0; if (row.${symbol(o.getString("field"))}) { flag$i = 1; }" }.joinToString("\n")
        helpers.append("function $fn(rows: ${src.type}, index: number, remaining: number, budget: number$quotaParams): $result {\nlet empty: ${src.type} = [];\nif (budget < 0) { return {m_found:false,m_rows:empty}; }\nif (index >= rows.length) { if (remaining === 0 && $leaf) { return {m_found:true,m_rows:empty}; } return {m_found:false,m_rows:empty}; }\nlet row: $rt = rows[index];\n$flags\nlet count: number = remaining;\nwhile (count >= 0) {\nlet tail: $result = $fn(rows,index + 1,remaining - count,budget - count * row.${symbol(cost)}$recArgs);\nif (tail.m_found) { let output: ${src.type} = []; let selected: $rt = ${copy(rt,"row",mapOf(q to "count"))}; output[output.length] = selected; for (let other: $rt of tail.m_rows) { output[output.length] = other; } return {m_found:true,m_rows:output}; }\ncount = count - 1;\n}\nreturn {m_found:false,m_rows:empty};\n}\n")
        b.append("if (${total.code} < 0 || math.floor(${total.code}) !== ${total.code} || ${total.code} - ${total.code} !== 0 || ${budget.code} - ${budget.code} !== 0) { throw {code:\"INVALID_ALLOCATION\",message:\"Invalid allocation inputs\"}; }\n")
        mins.forEach { b.append("if (${it.code} < 0 || math.floor(${it.code}) !== ${it.code} || ${it.code} - ${it.code} !== 0) { throw {code:\"INVALID_ALLOCATION\",message:\"Invalid quota\"}; }\n") }
        val row=fresh();b.append("for (let $row: $rt of ${src.code}) { if ($row.${symbol(cost)} < 0 || $row.${symbol(cost)} - $row.${symbol(cost)} !== 0) { throw {code:\"INVALID_ALLOCATION\",message:\"Invalid cost\"}; } }\n")
        val res=fresh();val dest=fresh();b.append("let $res: $result = $fn(${src.code},0,${total.code},${budget.code}${mins.joinToString(""){", ${it.code}"}});\nlet $dest: ${src.type} = $res.m_rows;\nif (!$res.m_found) {\n");val zeroRow=fresh();b.append("for (let $zeroRow: $rt of ${src.code}) { let cleared: $rt = ${copy(rt,zeroRow,mapOf(q to "0"))}; $dest[$dest.length] = cleared; }\n}\n");return V(src.type,dest)
    }
    private fun coverage(packages:V,catalogue:V,p:JSONObject,env:Map<String,V>,b:StringBuilder,chain:Set<String>):V {
        val rt=packages.type.element ?: reject("covers needs packages");if(catalogue.type.element!=rt)reject("covers catalogue type")
        val components=p.getString("components");val ct=field(rt,components).element ?: reject("components needs array");val key=p.getString("key");val itemId=p.getString("itemId");val qty=p.getString("quantity")
        requireType(V(field(rt,key),""),string);requireType(V(field(ct,itemId),""),string);requireType(V(field(ct,qty),""),number)
        val groups=array(p,"groups");val counts=groups.map{requireType(expr(it.getString("minimum"),env,b,chain),number)}
        val dest=fresh();val pkg=fresh();val component=fresh();val row=fresh();val known=fresh();val valid=fresh();val totals=groups.map{fresh()}
        b.append("let $dest: ${packages.type} = [];\nfor (let $pkg: $rt of ${packages.code}) {\nlet $valid: boolean = true;\n")
        totals.forEach{b.append("let $it: number = 0;\n")}
        b.append("for (let $component: $ct of $pkg.${symbol(components)}) { if ($component.${symbol(qty)} < 1 || math.floor($component.${symbol(qty)}) !== $component.${symbol(qty)} || $component.${symbol(qty)} - $component.${symbol(qty)} !== 0) { $valid = false; } let $known: boolean = false; for (let $row: $rt of ${catalogue.code}) { if ($row.${symbol(key)} === $component.${symbol(itemId)}) { $known = true;\n")
        groups.forEachIndexed { i,g -> val test=requireType(expr(g.getString("predicate"),env+("row" to V(rt,row)),b,chain),boolean);b.append("if (${test.code}) { ${totals[i]} = ${totals[i]} + $component.${symbol(qty)}; }\n") }
        b.append("} } if (!$known) { $valid = false; } }\n")
        val enough=counts.indices.joinToString(" && "){"${totals[it]} >= ${counts[it].code}"}.ifEmpty{"true"}
        b.append("if ($valid && $enough) { $dest[$dest.length] = $pkg; }\n}\n");return V(packages.type,dest)
    }
    fun assemble(p:JSONObject):JSONObject {
        records.clear(); declarations.clear(); helpers.clear(); optionalTypes.clear(); serial = 0
        try { return emit(p) } catch(e:JSONException){reject("Malformed behavioral plan: ${e.message}")}
    }
    private fun emit(p:JSONObject):JSONObject {
        val keys=listOf("records","state","nodes","derived","controls","actions","views","root")
        closed(p,*(keys+if(allowCustom)listOf("custom","reason")else emptyList()).toTypedArray())
        custom=if(allowCustom)array(p,"custom").associateBy{it.getString("id")}else emptyMap()
        if(allowCustom && (custom.isEmpty() || custom.size!=p.getJSONArray("custom").length() || p.getString("reason").isBlank()))reject("Custom sections require unique IDs and an uncovered-behavior reason")
        validateShapes(p)
        // Reserve declared names before resolving record field references.
        array(p,"records").forEach { r ->
            val n=symbol(r.getString("name"))
            if (records.put(n,linkedMapOf()) != null) reject("Duplicate record")
        }
        array(p,"records").forEach { r -> val fs=linkedMapOf<String,Ty>();array(r,"fields").forEach { f -> val n=name(f.getString("name"));if(fs.put(n,type(f.getString("type")))!=null)reject("Duplicate field") };records.remove(symbol(r.getString("name")));declare(symbol(r.getString("name")),fs) }
        fun checkRecord(t:Ty, active:Set<String>) {
            if (t.element!=null) { checkRecord(t.element,active); return }
            if (t.scalar) return
            if (t.name in active) reject("Recursive record type")
            fields(t).values.forEach { checkRecord(it,active+t.name) }
        }
        records.keys.forEach { checkRecord(Ty(it), emptySet()) }
        custom.values.forEach { section ->
            closed(section,"id","parameters","result","body")
            val parameters=array(section,"parameters")
            parameters.forEach { closed(it,"name","type") }
            if(parameters.map{it.getString("name")}.distinct().size!=parameters.size)reject("Duplicate custom parameter")
            val body=section.getString("body")
            val tokens=deal.lexer.Lexer(body,"custom-section").tokenize().tokens()
            if(tokens.any{it.lexeme() in setOf("import","export","class","function") && it.type()!=deal.ast.TokenType.STRING_LITERAL})reject("Custom section must be a function body")
            helpers.append("function custom_${name(section.getString("id"))}(${parameters.joinToString(","){name(it.getString("name"))+": "+type(it.getString("type"))}}): ${type(section.getString("result"))} {\n$body\n}\n")
        }
        val stateDefs=array(p,"state");val stateFields=linkedMapOf<String,Ty>("loading" to boolean,"error" to string,"sessionEpoch" to number)
        stateDefs.forEach { f -> val n=name(f.getString("name"));if(stateFields.put(n,type(f.getString("type")))!=null)reject("Duplicate state field") }
        val actions=array(p,"actions");val stepTypes=mutableMapOf<String,Ty>();val imports=linkedMapOf<String,String>()
        actions.forEach { a -> array(a,"steps").forEach { step ->
            if(step.getString("kind")=="call") {
                val module=step.getString("module");val fn=step.getString("function");val contract=contracts.singleOrNull{it.module==module} ?: reject("Undisclosed module $module")
                val function=contract.functions.singleOrNull{it.name==fn} ?: reject("Unknown function $module.$fn")
                val key=a.getString("id")+"/"+step.getString("id")
                stepTypes[key]=providerType(function.result,"Provider${serial++}");imports.getOrPut(module){"provider${imports.size}"}
            }
        } }
        val state=declare("State",stateFields)
        nodes=array(p,"nodes").associateBy { name(it.getString("id")) };if(nodes.size!=p.getJSONArray("nodes").length())reject("Duplicate expression ID")
        fun checkNode(id:String, active:Set<String>) {
            if(id.startsWith("$"))return
            if(id in active)reject("Expression cycle at $id")
            references(nodes[id] ?: reject("Unknown expression $id")).forEach { checkNode(it,active+id) }
        }
        nodes.keys.forEach { checkNode(it,emptySet()) }
        val base=mapOf("state" to V(state,"next"))
        val derived=array(p,"derived");val targets=derived.map{it.getString("field")};if(targets.distinct().size!=targets.size)reject("Duplicate derived field")
        fun dependencies(id:String,seen:Set<String> = emptySet()):Set<String> {
            if(id.startsWith("\$state."))return setOf(id.substringAfter("\$state.").substringBefore('.'))
            if(id.startsWith("$"))return emptySet()
            if(id in seen)reject("Expression cycle")
            val n=nodes[id]?:reject("Unknown expression $id")
            return references(n).flatMap{dependencies(it,seen+id)}.toSet()
        }
        val ordered=mutableListOf<JSONObject>();val pending=derived.toMutableList()
        while(pending.isNotEmpty()) {val ready=pending.firstOrNull { dependencies(it.getString("node")).intersect(targets.toSet()).all { d->ordered.any{it.getString("field")==d} } }?:reject("Derived cycle");ordered.add(ready);pending.remove(ready)}
        val recompute=StringBuilder("function recompute(state: State): State { let next: State = ${copy(state,"state")};\n")
        ordered.forEach { d -> val v=requireType(expr(d.getString("node"),base,recompute),field(state,d.getString("field")));recompute.append("next.${symbol(d.getString("field"))} = ${v.code};\n") };recompute.append("return next; }\n")
        val initial=stateDefs.associate { it.getString("name") to initial(field(state,it.getString("name")),dataValue(it.getString("initial"))) }+mapOf("loading" to "false","error" to "\"\"","sessionEpoch" to "0")
        val code=StringBuilder("export function initialState(): State { let next: State = ${copy(state,"",initial)}; return recompute(next); }\n")
        val actionTypes=linkedMapOf<String,Ty>()
        val controls=array(p,"controls")
        controls.forEach { c ->
            val f=c.getString("field");requireType(V(field(state,f),""),number);val a="Set"+name(f);val at=declare(symbol(a),linkedMapOf("value" to number));actionTypes[a]=at
            val lo=c.getDouble("minimum");val hi=c.getDouble("maximum");val step=c.getDouble("step")
            if(c.getString("widget")=="time" && (lo<0 || hi>1439))reject("Time control requires minute-of-day bounds")
            if(!lo.isFinite()||!hi.isFinite()||!step.isFinite()||lo!=kotlin.math.floor(lo)||hi!=kotlin.math.floor(hi)||lo<Int.MIN_VALUE||hi>Int.MAX_VALUE||lo>hi||step<=0||step!=kotlin.math.floor(step))reject("Invalid integral control bounds")
            val invalid=strings(c.getJSONArray("invalidate")).joinToString("\n") { requireType(V(field(state,it),""),boolean);"next.${symbol(it)} = false;" }
            code.append("// @ui-update\nexport function update${symbol(a)}(state: State, action: $at): State { let next: State = ${copy(state,"state")}; if (action.m_value - action.m_value !== 0 || math.floor(action.m_value) !== action.m_value || !values.inBounds(action.m_value,${decimal(lo)},${decimal(hi)})) { return next; } next.${symbol(f)} = action.m_value; $invalid return recompute(next); }\n")
        }
        actions.forEach { a ->
            val id=name(a.getString("id"));val fs=linkedMapOf<String,Ty>();array(a,"fields").forEach{fs[it.getString("name")]=type(it.getString("type"))}
            val at=declare(symbol(id),fs);if(actionTypes.put(id,at)!=null)reject("Duplicate action")
            val env=base+("action" to V(at,"action"));val steps=array(a,"steps");val async=steps.any{it.getString("kind")=="call"}
            val updates=StringBuilder();val assignments=StringBuilder()
            array(a,"updates").forEach{u->val f=u.getString("field");val v=requireType(expr(u.getString("node"),env,updates),field(state,f));assignments.append("next.${symbol(f)} = ${v.code};\n")}
            updates.append(assignments)
            array(p,"views").filter { it.getString("component")=="SessionClock" }.forEach { view ->
                array(view,"events").filter { it.getString("action")==id && it.getString("name")=="onTick" }.forEach { event ->
                    val payload=array(event,"payload").singleOrNull { it.getString("node")=="\$payload" } ?: reject("SessionClock requires one captured numeric payload")
                    val f=payload.getString("field");requireType(V(field(at,f),""),number)
                    updates.append("if (action.${symbol(f)} < 0) { next.m_sessionEpoch = state.m_sessionEpoch + 1; next.m_loading = false; }\n")
                }
            }
            code.append("// @ui-update\nexport function update${symbol(id)}(state: State, action: $at): State { let next: State = ${copy(state,"state")};\n$updates")
            if(async)code.append("next.m_loading = true; next.m_error = \"\"; next.m_sessionEpoch = state.m_sessionEpoch + 1;\n")
            code.append("return recompute(next); }\n")
            if(async) {
                val changedFields=steps.map { it.getString("bind") }.filter { it.isNotEmpty() }.toSet()+setOf("loading","error")
                val completionFields=linkedMapOf<String,Ty>("sessionEpoch" to number);stateFields.forEach { (f,t) -> if(f in changedFields)completionFields[f]=t }
                val done=declare("Done${serial++}",completionFields);val body=StringBuilder();val stepEnv=env.toMutableMap()
                steps.forEach { s -> when(s.getString("kind")) {
                    "call" -> {
                        val module=s.getString("module");val fn=s.getString("function");val function=contracts.single{it.module==module}.functions.single{it.name==fn};val args=strings(s.getJSONArray("args"))
                        if(args.size!=function.parameters.size)reject("Capability arity")
                        val guard=s.getString("when")
                        val condition=if(guard.isEmpty())"true" else requireType(expr(guard,stepEnv,body),boolean).code
                        val rt=stepTypes.getValue(id+"/"+s.getString("id"));val dest=fresh()
                        body.append("let $dest: $rt = ${default(rt)};\nif ($condition) {\n")
                        val av=args.mapIndexed{i,n->encodeArgument(expr(n,stepEnv,body),function.parameters[i].type,body)}
                        val result=decode(rt,"await ${imports.getValue(module)}.$fn(${av.joinToString(",")})",body)
                        body.append("$dest = ${result.code};\n")
                        stepEnv[s.getString("id")]=V(rt,dest)
                        stepEnv[s.getString("id")+"Present"]=V(boolean,condition)
                        val bind=s.getString("bind");if(bind.isNotEmpty()){val bt=field(state,bind);val projected=expr(s.getString("node"),stepEnv,body);requireType(projected,bt);body.append("next.${symbol(bind)} = ${projected.code};\n")}
                        body.append("}\n")
                    }
                    "set" -> {val f=s.getString("bind");val v=requireType(expr(s.getString("node"),stepEnv,body),field(state,f));body.append("next.${symbol(f)} = ${v.code};\n")}
                    else->reject("Unknown workflow step")
                } }
                code.append("// @ui-effect\nexport async function run${symbol(id)}(state: State, action: $at): $done { let next: State = ${copy(state,"state")}; try {\n$body next.m_loading = false; next.m_error = \"\"; return ${copy(done,"next")}; } catch (error) { return ${copy(done,"state",mapOf("loading" to "false","error" to quote("Check access and try again")))}; } }\n")
                val completionValues=completionFields.keys.associateWith { "action.${symbol(it)}" }
                code.append("// @ui-update\nexport function finish${symbol(id)}(state: State, action: $done): State { if (state.m_sessionEpoch !== action.m_sessionEpoch) { return state; } let next: State = ${copy(state,"state",completionValues)}; return recompute(next); }\n")
            } else if(steps.isNotEmpty())reject("Workflow without provider call: put local changes in updates")
        }
        val views=array(p,"views").associateBy{it.getString("id")};if(views.size!=p.getJSONArray("views").length())reject("Duplicate view ID")
        fun viewValue(id:String,env:Map<String,V>):String {
            if(id.startsWith("$")) {
                val v=ref(id,env);if(!v.type.scalar)reject("View requires scalar binding");return v.code
            }
            val node=nodes[id] ?: reject("Unknown view binding $id")
            if(node.getString("op")!="literal")reject("View computations must bind derived state fields")
            val params=node.getJSONObject("params");val t=type(params.getString("type"))
            if(!t.scalar)reject("View literal must be scalar")
            return initial(t,dataValue(params.getString("value")))
        }
        fun render(id:String,env:Map<String,V>,active:Set<String> = emptySet(),row:Boolean=false):String {
            if(id in active)reject("View cycle");val v=views[id]?:reject("Unknown view $id");val each=v.getString("each")
            if(each.isNotEmpty()) {
                if(!each.startsWith("$"))reject("List must bind prepared state")
                val source=ref(each,env);val rt=source.type.element?:reject("List needs array");val rv="row${serial++}";val nested=env+("row" to V(rt,rv));val key=ref(v.getString("key"),nested);if(key.type !in setOf(number,string))reject("List key type")
                val copy=JSONObject(v.toString()).put("each","")
                // Render this same node's component with its row lexical scope.
                return "ForEach(${source.code}, $rv: app.$rt, key: ${key.code}) {\n"+renderComponent(copy,nested,active+id,true,actionTypes,::viewValue){ child,e,a,r -> render(child,e,a,r) }+"}\n"
            }
            return renderComponent(v,env,active+id,row,actionTypes,::viewValue){child,e,a,r->render(child,e,a,r)}
        }
        val uiEnv=mapOf("state" to V(state,"state"));val view=render(p.getString("root"),uiEnv)
        val controlUi=controls.joinToString("\n") { c ->
            val f=c.getString("field");val widget=c.getString("widget");if(widget !in setOf("integer","money","time"))reject("Unknown control widget")
            if(widget=="time")"ui.TimeField(value: state.${symbol(f)}, label: ${quote(c.getString("label"))}, enabled: !state.m_loading, onChange: action app.${symbol("Set"+f)} {m_value:payload})"
            else "ui.IntField(value: state.${symbol(f)}, label: ${quote(c.getString("label"))}, minimum:${decimal(c.getDouble("minimum"))}, maximum:${decimal(c.getDouble("maximum"))}, step:${decimal(c.getDouble("step"))}, format:${quote(if(widget=="money")"euro-cents" else "integer")}, enabled: !state.m_loading, onChange: action app.${symbol("Set"+f)} {m_value:payload})"
        }
        val importsText=imports.entries.joinToString("\n"){(m,a)->"import * as $a from ${quote(m)};"}
        val deal="$importsText\nimport * as math from \"std/math\";\nimport * as tables from \"std/table\";\nimport * as values from \"ui/values\";\n$declarations$helpers$recompute$code\nexport function main(): null { return null; }\n"
        val ui="import * as app from \"./experience\";\nimport * as ui from \"./platform.dealui-pack\";\n// @ui-root\nexport view Workspace(state: app.State): View { ui.Column() {\n$controlUi\nWhen(state.m_loading) { ui.Progress(label: \"Loading…\") }\nWhen(state.m_error !== \"\") { ui.Notice(text:state.m_error,tone:\"error\") }\n$view\n} }\n"
        return JSONObject().put("deal",deal).put("dealui",ui)
    }
    private fun renderComponent(v:JSONObject,env:Map<String,V>,active:Set<String>,row:Boolean,actions:Map<String,Ty>,value:(String,Map<String,V>)->String,render:(String,Map<String,V>,Set<String>,Boolean)->String):String {
        val component=name(v.getString("component"));val props=array(v,"props").map{symbolToProp(it.getString("name"))+": "+value(it.getString("node"),env)}.toMutableList()
        array(v,"events").forEach { e -> val a=e.getString("action");val t=actions[a]?:reject("Unknown event action $a");val vals=array(e,"payload");if(vals.map{it.getString("field")}.toSet()!=fields(t).keys)reject("Action payload fields")
            val payload=vals.joinToString(","){f->val node=f.getString("node");symbol(f.getString("field"))+": "+if(node=="\$payload")"payload" else value(node,env)}
            props.add(symbolToProp(e.getString("name"))+": action app.$t {$payload}")
        }
        val children=strings(v.getJSONArray("children"));var out="ui.$component(${props.joinToString(",")})"+(if(children.isEmpty())"\n" else " {\n"+children.joinToString(""){render(it,env,active,row)}+"}\n")
        val condition=v.getString("when");if(condition.isNotEmpty())out="When(${value(condition,env)}) {\n$out}\n"
        return out
    }
    private fun symbolToProp(s:String)=name(s)

    private fun references(node: JSONObject): List<String> {
        val refs = strings(node.getJSONArray("args")).toMutableList()
        val p = node.getJSONObject("params")
        when (node.getString("op")) {
            "record", "update" -> refs.addAll(bindings(p,"fields").values)
            "allocate" -> array(p,"quotas").forEach { refs.add(it.getString("minimum")) }
            "covers" -> array(p,"groups").forEach { refs.add(it.getString("minimum")); refs.add(it.getString("predicate")) }
        }
        return refs
    }

    private fun dataValue(text:String):Any {
        val parser=JSONTokener(text);val value=parser.nextValue()
        if(parser.nextClean()!='\u0000')reject("Trailing initializer data")
        return value
    }
    private fun validateShapes(p: JSONObject) {
        fun unique(items:List<JSONObject>,key:String) { if(items.map{it.getString(key)}.distinct().size!=items.size)reject("Duplicate $key") }
        unique(array(p,"controls"),"field")
        array(p,"records").forEach { r -> closed(r,"name","fields"); array(r,"fields").forEach { closed(it,"name","type") } }
        array(p,"state").forEach { closed(it,"name","type","initial") }
        array(p,"derived").forEach { closed(it,"field","node") }
        array(p,"controls").forEach { closed(it,"field","label","widget","minimum","maximum","step","invalidate") }
        array(p,"actions").forEach { a ->
            closed(a,"id","fields","updates","steps")
            array(a,"fields").forEach { closed(it,"name","type") };unique(array(a,"fields"),"name")
            array(a,"updates").forEach { closed(it,"field","node") };unique(array(a,"updates"),"field")
            val steps=mutableSetOf("state","action","row","left","right")
            array(a,"steps").forEach { s -> when(s.getString("kind")) {
                "call" -> { closed(s,"kind","id","module","function","args","bind","node","when"); val id=name(s.getString("id"));if(!steps.add(id)||!steps.add(id+"Present"))reject("Duplicate/reserved workflow reference") }
                "set" -> closed(s,"kind","bind","node")
                else -> reject("Unknown workflow step")
            } }
        }
        array(p,"views").forEach { v ->
            closed(v,"id","component","props","events","children","each","key","when")
            array(v,"props").forEach { closed(it,"name","node") };unique(array(v,"props"),"name");unique(array(v,"events"),"name")
            if(array(v,"props").map{it.getString("name")}.intersect(array(v,"events").map{it.getString("name")}.toSet()).isNotEmpty())reject("Duplicate prop/event")
            array(v,"events").forEach { e -> closed(e,"name","action","payload"); array(e,"payload").forEach { closed(it,"field","node") };unique(array(e,"payload"),"field") }
        }
        val arities=mapOf("literal" to 0,"record" to 0,"add" to 2,"subtract" to 2,"multiply" to 2,"divide" to 2,
            "floor" to 1,"ceil" to 1,"abs" to 1,"eq" to 2,"ne" to 2,"lt" to 2,"le" to 2,"gt" to 2,"ge" to 2,
            "and" to 2,"or" to 2,"not" to 1,"choose" to 3,"integer" to 1,"clock" to 1,"length" to 1,
            "filter" to 2,"project" to 2,"sum" to 2,"any" to 2,"all" to 2,"update" to 2,"first" to 1,"lookup" to 2,
            "sort" to 1,"basket" to 1,"allocate" to 3,"covers" to 2,"join" to 4)
        array(p,"nodes").forEach { n ->
            closed(n,"id","op","args","params")
            val op=n.getString("op");val params=n.getJSONObject("params")
            val arity=arities[op]
            if(arity==null && op !in setOf("array","concat") && !(allowCustom && op=="custom"))reject("Unknown operation $op")
            if(arity!=null && n.getJSONArray("args").length()!=arity)reject("Wrong arity for $op")
            when(op) {
                "custom" -> closed(params,"section")
                "literal" -> closed(params,"type","value")
                "record" -> { closed(params,"type","fields"); array(params,"fields").forEach { closed(it,"field","node") } }
                "update" -> { closed(params,"fields"); array(params,"fields").forEach { closed(it,"field","node") } }
                "array" -> closed(params,"element")
                "lookup" -> closed(params,"key")
                "sort" -> closed(params,"field")
                "basket" -> closed(params,"id","quantity")
                "allocate" -> { closed(params,"quantity","cost","quotas"); array(params,"quotas").forEach { closed(it,"field","minimum") } }
                "covers" -> { closed(params,"components","key","itemId","quantity","groups"); array(params,"groups").forEach { closed(it,"predicate","minimum") } }
                else -> closed(params)
            }
        }
    }
}
