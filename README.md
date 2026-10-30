[![Release](https://jitpack.io/v/umjammer/vavi-mcp-refactoring.svg)](https://jitpack.io/#umjammer/vavi-mcp-refactoring)
[![Java CI](https://github.com/umjammer/vavi-mcp-refactoring/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-mcp-refactoring/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-mcp-refactoring/actions/workflows/codeql.yml/badge.svg)](https://github.com/umjammer/vavi-mcp-refactoring/actions/workflows/codeql.yml)
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
| infer-local-names | names the parameters, then JD style locals (`localQDRect`, `arrayOfInt2`, `str`, `localIOException` ...) |
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

### local variables

`infer-local-names` (cli `--locals`) names the parameters first, then the locals, in priority order

 1. a catch parameter: `e`
 1. the element of a for-each over `listeners`: `listener`
 1. a field it is assigned to
 1. the parameter it is passed to, also as an out parameter: `int[] arrayOfInt = new int[1]; GetMovieBox(movie, arrayOfInt)` → `box`
 1. a getter it is initialized with: `x = movie.getTrack(1)` → `track` (not `getIntAt`, `getStringFromPointer` ...)
 1. the getter it is returned from: `return x;` in `getTrack()` → `track`
 1. its type

references are resolved by java's scoping rules (blocks, `for`, `catch`, try resources, switch blocks, pattern variables,
locals of nested classes and lambda parameters shadowing), a local whose references can't be told apart is left alone.
a new name never clashes with a variable whose scope overlaps or with an unqualified reference in its scope,
so sibling blocks may share a name.
in vavi-media-quicktime it named 1,904 of 2,071 locals, and the result compiles and passes the tests.

#### reused locals

with `split_reused` (cli `--split`) a local JD reuses for different values is split into one variable per value first.

```java
Object localObject1;                         Object localObject1;
do {                                         do {
    localObject1 = getTarget();                  localObject1 = getTarget();
    ...                                          ...
} while (...);                               } while (...);
localObject1 = this.list;              →     TickleNode tickleNode = this.list;
... ((TickleNode) localObject1).next ...     ... tickleNode.next ...
```

a variable is split at `v = expr;` statements directly in its declaring block (and not reading `v`),
nothing assigned before can be read after such a statement, so the pieces are independent.
the case groups of a `switch` statement count as one block (a jump to a later case leaves the variable unassigned).
a variable declared in a `for` loop is not split, its condition and update carry the value around, it can be retyped:
`for (Object o = this.head; o != null; o = ((Node) o).next)` → `for (Node node = this.head; node != null; node = node.next)`.
an `Object` piece gets the type of its values when they are all casts, `new` or variables/fields of one type,
or of any other expression the javaparser symbol solver resolves (method return values of the project and the jdk, generics like `List<Node>.get(0)`),
and it is used only cast to that type, compared, tested by `instanceof`, assigned or returned (an argument could change the overload),
then the casts are gone. a declaration whose value is never read is joined with the first assignment.
with `var_over` (cli `--var-over n`) a retyped piece whose type is longer than n characters is declared `var`,
when its initializer has exactly that type (a cast, a `new` that is not anonymous, a variable or a field).

#### names by hand

```
Owner.method/arity:localName      newName   # all locals of the name in the method
Owner.method(int):str@2           title     # the 2nd one in source order
```

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
    [--unwrap ID] [--type-name QDRect=rect] [--no-type-fallback] [--locals] [--local-pattern re] [--split] [--var-over n] [--report file] [--apply] srcdir...
```

## References

 * [javaparser](https://github.com/javaparser/javaparser)
 * [mcp java sdk](https://github.com/modelcontextprotocol/java-sdk)

## TODO

 * nothing for now
