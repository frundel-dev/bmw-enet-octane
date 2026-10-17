#!/usr/bin/env node
/**
 * REA-only acceptance lane for the public, pinned ApiDemos fixture.
 * Do not execute the APK; no direct JADX CLI calls; no third-party APK inputs.
 */
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {mkdirSync,readFileSync,writeFileSync} from "node:fs";
import {resolve,join} from "node:path";

const output=resolve(process.env.REA_REPORT_DIR || "rea-results");
const apk=resolve(process.env.REA_TEST_APK || "rea-input/ApiDemos-debug.apk");
const expectedHash="a9eecf37b26cd084855c530db81c2bb1b91f4c1b095a04f47aa7c20e2791f686";
const expectedClass="io.appium.android.apis.ApiDemos";
mkdirSync(output,{recursive:true});
const report={
  status:"FAILED",
  target:"Appium ApiDemos 6.0.18 (Apache-2.0, public test fixture)",
  official_rea:"morluto/rea",
  apk_sha256_expected:expectedHash,
  static_analysis_only:true,
  apk_executed:false,
  operations:[]
};
const saveReport=()=>writeFileSync(join(output,"acceptance.json"),JSON.stringify(report,null,2)+"\n");
const sha256=path=>createHash("sha256").update(readFileSync(path)).digest("hex");
const run=(operation,args)=>{
  const start=Date.now();
  const result=spawnSync("rea",[...args,"--format","json"],{
    encoding:"utf8",maxBuffer:32*1024*1024,
    timeout:180_000,env:{...process.env}
  });
  const base=operation.replace(/[^a-z0-9_-]/gi,"_");
  writeFileSync(join(output,base+".stdout.json"),result.stdout||"");
  writeFileSync(join(output,base+".stderr.txt"),result.stderr||"");
  const entry={
    operation,exit_code:result.status,elapsed_ms:Date.now()-start,
    spawn_error:result.error?String(result.error):null
  };
  report.operations.push(entry);
  saveReport();
  if(result.error||result.status!==0)throw Error(
    operation+" failed: "+String(result.error||"exit "+result.status)+
    "; inspect "+base+".stderr.txt and .stdout.json");
  let evidence;
  try{evidence=JSON.parse(result.stdout);}
  catch(error){throw Error(operation+" emitted non-JSON CLI output: "+String(error));}
  assert.equal(evidence.operation,operation,operation+": wrong REA operation");
  assert.equal(evidence.subject?.digest?.sha256,expectedHash,
    operation+": wrong APK provenance");
  assert.ok(evidence.normalized_result &&
    typeof evidence.normalized_result==="object",operation+": missing normalized evidence");
  entry.evidence_id=evidence.evidence_id??null;
  entry.provider=evidence.provider??null;
  entry.result_validated=true;
  saveReport();
  return evidence.normalized_result;
};
try {
  report.apk_sha256=sha256(apk);
  assert.equal(report.apk_sha256,expectedHash,"Public test APK digest differs");
  const jar=process.env.REA_JADX_MCP_JAR;
  assert.ok(jar&&jar.startsWith("/"),"REA_JADX_MCP_JAR must be absolute");
  const expectedJarHash="6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75";
  report.jadx_jar_sha256=sha256(jar);
  assert.equal(report.jadx_jar_sha256,expectedJarHash,"Wrong upstream JADX release JAR");
  const npm=spawnSync("npm",["ls","-g","rea-agents","--json"],{
    encoding:"utf8",timeout:15000,maxBuffer:2*1024*1024
  });
  assert.equal(npm.status,0,"REA global npm installation is not inspectable");
  report.rea_version=JSON.parse(npm.stdout).dependencies?.["rea-agents"]?.version;
  assert.equal(report.rea_version,"6.5.0","Installed REA version differs from approved 6.5.0");
  report.node_version=process.version;
  saveReport();

  const pkg=run("inspect_android_package",["inspect-android-package",apk]);
  assert.equal(pkg.package_name,"io.appium.android.apis",
    "REA did not resolve the expected public APK");
  assert.equal(pkg.version_name,"6.0.18");
  assert.ok(pkg.engine,"REA returned no producing engine identity");
  assert.ok(pkg.manifest?.text?.includes("io.appium.android.apis"),
    "REA returned no actual decoded APK manifest");

  // Empty REA query returns all class-name rows; no direct JADX invocation.
  const classes=run("search_android_classes",["search-android-classes",apk,""]);
  assert.ok(Array.isArray(classes.matches),"REA did not return a class-name list");
  assert.ok(classes.matches.includes(expectedClass),
    "REA class inventory omits ApiDemos");
  writeFileSync(join(output,"classes.txt"),classes.matches.join("\n")+"\n");
  report.class_inventory_rows=classes.matches.length;

  const klass=run("inspect_android_class",["inspect-android-class",apk,expectedClass]);
  assert.ok(Array.isArray(klass.methods) &&
    klass.methods.some(method=>method.name==="onCreate"),
    "REA class inventory does not list onCreate");

  const method=run("inspect_android_method",
    ["inspect-android-method",apk,expectedClass,"onCreate"]);
  assert.equal(method.body_status,"available","REA did not decompile method body");
  assert.equal(method.representation,"java","REA returned no Java method body");
  assert.equal(method.source?.status,"complete","REA returned incomplete source");
  assert.ok(method.source.text.includes("getIntent()") &&
    method.source.text.includes("setListAdapter"),
    "REA method does not contain expected fixture operations");
  writeFileSync(join(output,"method-onCreate.java.txt"),method.source.text+"\n");

  assert.equal(sha256(apk),expectedHash,"APK bytes changed during analysis");
  report.apk_sha256_after=sha256(apk);
  report.class_name=expectedClass;
  report.method_name="onCreate";
  report.method_body_status=method.body_status;
  report.engine=pkg.engine;
  report.status="PASS";
  saveReport();
  console.log("PASS: real REA CLI/JADX APK, class inventory and onCreate method");
} catch(error) {
  report.error=String(error?.stack||error);
  saveReport();
  console.error("FAIL REA Android acceptance:",report.error);
  process.exitCode=1;
}
