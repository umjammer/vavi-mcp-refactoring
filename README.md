[![Release](https://jitpack.io/v/umjammer/vavi-mcp-refactoring.svg)](https://jitpack.io/#umjammer/vavi-mcp-refactoring)
[![Java CI](https://github.com/umjammer/vavi-mcp-refactoring/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-mcp-refactoring/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-mcp-refactoring/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/umjammer/vavi-mcp-refactoring/actions/workflows/codeql-analysis.yml)
![Java](https://img.shields.io/badge/Java-25-b07219)

# vavi-mcp-refactoring

🔧 Model Context Protocol server for refactoring java sources.

the first tool set gives decompiled code its parameter names back (`paramInt1`, `paramArrayOfByte` ...),
extracted from deobfuscating [vavi-media-quicktime](https://github.com/umjammer/vavi-media-quicktime),
where it named 8,300 parameters in 400 files.

## Install

 * [maven](https://jitpack.io/#umjammer/vavi-mcp-refactoring)

## Usage

### mcp server (stdio)

```
java -cp ... vavi.refactoring.mcp.RefactoringMCP
```

e.g. for claude code

```
claude mcp add refactoring -- java -cp /path/to/vavi-mcp-refactoring.jar:... vavi.refactoring.mcp.RefactoringMCP
```

### tools

| name | description |
|------|-------------|
| find-obfuscated-parameters | lists methods with obfuscated parameters, optionally with their source |
| infer-parameter-names | infers names (dry run by default), `apply: true` writes them |
| rename-parameter | renames one parameter in a file, refuses clashing names |

### how names are found

in priority order

 1. a name map given by hand
 1. the same method in a super or sub type
 1. a setter: `setVolume(x)` → `volume`
 1. a field it is assigned to: `this.rate = x` → `rate`
 1. the parameter of the method or constructor it is passed to.
    the overload is chosen by the argument types and all overloads have to agree.
    names propagate until nothing changes, so the names of a low level api (`MoveTo(short h, short v)`) reach every wrapper
 1. its type: `Movie` → `movie`, `QDPoint[]` → `points`, `MouseEvent` → `e` (`type_fallback`)

a name is never taken when it clashes with a parameter, a local or an unqualified reference in the method,
so the code keeps its meaning (`volume = paramFloat` is not renamed to `volume = volume`).
the edits are made in place, formatting, comments and line numbers stay as they are,
references in nested lambdas/classes that declare the same name are left alone, and javadoc `@param` tags follow.

by default only JD-GUI/JAD style names that spell the declared type are renamed
(`int paramInt2`, `byte[] paramArrayOfByte`, `Movie paramMovie`), real names like `param1` or `paramBlock` are kept.
give `pattern` (e.g. `var\d+` for fernflower) to rename other styles.

### name map

```
# Owner.method/arity        names...
VRManager.QTVRSetPanAngle/2  qtvr panAngle
QDRect.<init>(byte[],byte[]) rect fixedRect   # a typed key wins over the arity key
Movie.GetMoviePreviewTime/3  =movie time -    # "-" keeps, "=" also replaces a name that is not obfuscated
```

a workflow that worked for 8,000 parameters:

 1. `infer-parameter-names` dry run, look at the renames by reason (`arg`, `setter`, `type` ...) and the unresolved methods
 1. names that are wrong in the sources spread, put them into `poison_names`
 1. `find-obfuscated-parameters` with `include_source` for the rest, name them in a map file
 1. `infer-parameter-names` with `name_map_file` and `apply: true`, compile and test

### command line

```
java -cp ... vavi.refactoring.ParameterRenamerCli [--map file] [--pattern regex] [--poison a,b] \
    [--unwrap ID] [--type-name QDRect=rect] [--no-type-fallback] [--report file] [--apply] srcdir...
```

## References

 * [javaparser](https://github.com/javaparser/javaparser)
 * [mcp java sdk](https://github.com/modelcontextprotocol/java-sdk)

## TODO

 * more refactorings (local variables `localObject1`, `arrayOfInt` ...)
 * exit when stdin is closed
