package org.openl.rules.calc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.function.Predicate;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;

import org.openl.OpenL;
import org.openl.base.INamedThing;
import org.openl.binding.IBindingContext;
import org.openl.binding.IBoundMethodNode;
import org.openl.binding.exception.DuplicatedVarException;
import org.openl.binding.impl.NodeType;
import org.openl.binding.impl.NodeUsage;
import org.openl.binding.impl.SimpleNodeUsage;
import org.openl.binding.impl.cast.IOneElementArrayCast;
import org.openl.binding.impl.cast.IOpenCast;
import org.openl.binding.impl.component.ComponentOpenClass;
import org.openl.engine.OpenLManager;
import org.openl.exception.OpenLCompilationException;
import org.openl.meta.IMetaHolder;
import org.openl.meta.IMetaInfo;
import org.openl.meta.ValueMetaInfo;
import org.openl.rules.binding.RuleRowHelper;
import org.openl.rules.calc.element.SpreadsheetCell;
import org.openl.rules.calc.element.SpreadsheetCellField;
import org.openl.rules.calc.element.SpreadsheetCellRefType;
import org.openl.rules.calc.element.SpreadsheetCellType;
import org.openl.rules.calc.element.SpreadsheetExpressionMarker;
import org.openl.rules.calc.element.SpreadsheetStructureBuilderHolder;
import org.openl.rules.calc.result.ArrayResultBuilder;
import org.openl.rules.calc.result.EmptyResultBuilder;
import org.openl.rules.calc.result.IResultBuilder;
import org.openl.rules.calc.result.ScalarResultBuilder;
import org.openl.rules.calc.result.SpreadsheetResultBuilder;
import org.openl.rules.constants.ConstantOpenField;
import org.openl.rules.convertor.String2DataConvertorFactory;
import org.openl.rules.lang.xls.binding.XlsModuleOpenClass;
import org.openl.rules.lang.xls.syntax.TableSyntaxNode;
import org.openl.rules.lang.xls.types.CellMetaInfo;
import org.openl.rules.lang.xls.types.meta.SpreadsheetMetaInfoReader;
import org.openl.rules.table.ICell;
import org.openl.rules.table.IGridTable;
import org.openl.rules.table.ILogicalTable;
import org.openl.rules.table.LogicalTableHelper;
import org.openl.rules.table.openl.GridCellSourceCodeModule;
import org.openl.source.IOpenSourceCodeModule;
import org.openl.source.impl.SubTextSourceCodeModule;
import org.openl.syntax.exception.SyntaxNodeException;
import org.openl.syntax.exception.SyntaxNodeExceptionUtils;
import org.openl.syntax.impl.ISyntaxConstants;
import org.openl.syntax.impl.IdentifierNode;
import org.openl.syntax.impl.Tokenizer;
import org.openl.types.IAggregateInfo;
import org.openl.types.IMethodSignature;
import org.openl.types.IOpenClass;
import org.openl.types.IOpenField;
import org.openl.types.IOpenMethod;
import org.openl.types.IOpenMethodHeader;
import org.openl.types.NullOpenClass;
import org.openl.types.impl.CompositeMethod;
import org.openl.types.impl.OpenMethodHeader;
import org.openl.types.java.JavaOpenClass;
import org.openl.util.JavaKeywordUtils;
import org.openl.util.MessageUtils;
import org.openl.util.OpenClassUtils;
import org.openl.util.StringUtils;
import org.openl.util.text.AbsolutePosition;
import org.openl.util.text.ILocation;
import org.openl.util.text.IPosition;
import org.openl.util.text.LocationUtils;
import org.openl.util.text.TextInfo;
import org.openl.util.text.TextInterval;

public class SpreadsheetStructureBuilder {

    public static final String DOLLAR_SIGN = "$";

    private IBindingContext spreadsheetBindingContext;

    private final IOpenMethodHeader spreadsheetHeader;

    private final XlsModuleOpenClass xlsModuleOpenClass;

    private final SpreadsheetStructureBuilderHolder spreadsheetStructureBuilderHolder = new SpreadsheetStructureBuilderHolder(
        this);

    public static final ThreadLocal<Stack<Set<SpreadsheetCell>>> preventCellsLoopingOnThis = new ThreadLocal<>();

    public SpreadsheetStructureBuilderHolder getSpreadsheetStructureBuilderHolder() {
        return spreadsheetStructureBuilderHolder;
    }

    public SpreadsheetStructureBuilder(TableSyntaxNode tableSyntaxNode, IBindingContext bindingContext,
            IOpenMethodHeader spreadsheetHeader,
            XlsModuleOpenClass xlsModuleOpenClass) {
        this.tableSyntaxNode = tableSyntaxNode;
        this.columnNamesTable = tableSyntaxNode.getTableBody().getRow(0).getColumns(1);
        this.rowNamesTable = tableSyntaxNode.getTableBody().getColumn(0).getRows(1);
        this.bindingContext = bindingContext;
        this.spreadsheetHeader = spreadsheetHeader;
        this.xlsModuleOpenClass = xlsModuleOpenClass;
        addRowHeaders();
        addColumnHeaders();
        buildHeaderDefinitions();
        buildReturnCells(spreadsheetHeader.getType());
    }

    private final Map<Integer, IBindingContext> rowContexts = new HashMap<>();
    private final Map<Integer, SpreadsheetOpenClass> colComponentOpenClasses = new HashMap<>();
    private final Map<Integer, Map<Integer, SpreadsheetContext>> spreadsheetResultContexts = new HashMap<>();

    private SpreadsheetCell[][] cells;

    private final List<SpreadsheetCell> extractedCellValues = new ArrayList<>();

    private volatile boolean cellsExtracted = false;

    /**
     * Extract cell values from the source spreadsheet table.
     *
     * @return cells of spreadsheet with its values
     */
    public SpreadsheetCell[][] getCells() {
        if (!cellsExtracted) {
            synchronized (this) {
                if (!cellsExtracted) {
                    try {
                        extractCellValues();
                    } finally {
                        cellsExtracted = true;
                    }
                }
            }
        }
        return cells;
    }

    /**
     * Add to {@link SpreadsheetOpenClass} fields that are represented by spreadsheet cells.
     *
     * @param spreadsheetType open class of the spreadsheet
     */
    public void addCellFields(SpreadsheetOpenClass spreadsheetType, boolean autoType) {
        IBindingContext generalBindingContext = bindingContext;

        int rowsCount = this.getHeight();
        int columnsCount = this.getWidth();

        // create cells according to the size of the spreadsheet
        cells = new SpreadsheetCell[rowsCount][columnsCount];

        // create the binding context for the spreadsheet level
        spreadsheetBindingContext = new SpreadsheetContext(generalBindingContext, spreadsheetType, xlsModuleOpenClass);

        for (int rowIndex = 0; rowIndex < rowsCount; rowIndex++) {
            for (int columnIndex = 0; columnIndex < columnsCount; columnIndex++) {
                // build spreadsheet cell
                SpreadsheetCell spreadsheetCell = buildCell(rowIndex, columnIndex, autoType);

                // init cells array with appropriate cell
                cells[rowIndex][columnIndex] = spreadsheetCell;

                // create and add field of the cell to the spreadsheetType
                addSpreadsheetFields(spreadsheetType, rowIndex, columnIndex);
            }
        }
    }

    private void extractCellValues() {
        int rowsCount = this.getHeight();
        int columnsCount = this.getWidth();

        for (int rowIndex = 0; rowIndex < rowsCount; rowIndex++) {
            IBindingContext rowBindingContext = getRowContext(rowIndex);

            for (int columnIndex = 0; columnIndex < columnsCount; columnIndex++) {
                boolean found = false;
                for (SpreadsheetCell cell : extractedCellValues) {
                    int row = cell.getRowIndex();
                    int column = cell.getColumnIndex();
                    if (row == rowIndex && columnIndex == column) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    extractCellValue(rowBindingContext, rowIndex, columnIndex);
                }
            }
        }
    }

    public IOpenClass makeType(SpreadsheetCell cell) {
        if (cell.getType() == null) {
            Stack<Set<SpreadsheetCell>> stack = preventCellsLoopingOnThis.get();
            boolean f = stack == null;
            try {
                if (f) {
                    preventCellsLoopingOnThis.set(stack = new Stack<>());
                }
                Set<SpreadsheetCell> cellInProgressSet;
                if (stack.isEmpty()) {
                    cellInProgressSet = new HashSet<>();
                    stack.push(cellInProgressSet);
                } else {
                    cellInProgressSet = stack.peek();
                }
                if (!cellInProgressSet.contains(cell)) {
                    try {
                        cellInProgressSet.add(cell);
                        int rowIndex = cell.getRowIndex();
                        int columnIndex = cell.getColumnIndex();
                        IBindingContext rowContext = getRowContext(rowIndex);
                        extractCellValue(rowContext, rowIndex, columnIndex);
                        extractedCellValues.add(cell);
                    } finally {
                        cellInProgressSet.remove(cell);
                    }
                } else {
                    return JavaOpenClass.OBJECT;
                }
            } finally {
                if (f) {
                    preventCellsLoopingOnThis.remove();
                }
            }
        }
        return cell.getType();
    }

    private void extractCellValue(IBindingContext rowBindingContext, int rowIndex, int columnIndex) {
        SpreadsheetCell spreadsheetCell = cells[rowIndex][columnIndex];

        if (columnHeaders.get(columnIndex) == null || rowHeaders.get(rowIndex) == null) {
            spreadsheetCell.setValue(null);
            return;
        }

        ILogicalTable cell = LogicalTableHelper.mergeBounds(
            rowNamesTable.getRow(rowIndex),
            columnNamesTable.getColumn(columnIndex));

        IOpenSourceCodeModule source = new GridCellSourceCodeModule(cell.getSource(), spreadsheetBindingContext);
        String code = StringUtils.trimToNull(source.getCode());

        String name = getSpreadsheetCellFieldName(columnHeaders.get(columnIndex).getDefinitionName(),
            rowHeaders.get(rowIndex).getDefinitionName());

        IOpenClass type = spreadsheetCell.getType();

        if (code == null) {
            spreadsheetCell.setValue(type.nullObject());
        } else if (SpreadsheetExpressionMarker.isFormula(code)) {

            int end = 0;
            if (code.startsWith(SpreadsheetExpressionMarker.OPEN_CURLY_BRACKET.getSymbol())) {
                end = -1;
            }

            IOpenSourceCodeModule srcCode = new SubTextSourceCodeModule(source, 1, end);
            IMethodSignature signature = spreadsheetHeader.getSignature();
            IOpenClass declaringClass = spreadsheetHeader.getDeclaringClass();
            IOpenMethodHeader header = new OpenMethodHeader(name, type, signature, declaringClass);
            IBindingContext columnBindingContext = getColumnContext(columnIndex, rowIndex, rowBindingContext);
            OpenL openl = columnBindingContext.getOpenL();
            // columnBindingContext - is never null
            try {
                IOpenMethod method;
                if (header.getType() == null) {
                    method = OpenLManager.makeMethodWithUnknownType(openl,
                        srcCode,
                        name,
                        signature,
                        declaringClass,
                        columnBindingContext);
                    spreadsheetCell.setType(method.getType() != null ? method.getType() : NullOpenClass.the);
                } else {
                    method = OpenLManager.makeMethod(openl, srcCode, header, columnBindingContext);
                }
                spreadsheetCell.setValue(method);
            } catch (Exception | LinkageError e) {
                spreadsheetCell.setType(NullOpenClass.the);
                String message = String.format("Cannot parse cell value '%s' to the necessary type.", code);
                spreadsheetBindingContext.addError(SyntaxNodeExceptionUtils
                    .createError(message, e, LocationUtils.createTextInterval(source.getCode()), source));
            }

        } else if (spreadsheetCell.isConstantCell()) {
            try {
                IOpenField openField = rowBindingContext.findVar(ISyntaxConstants.THIS_NAMESPACE, code, true);
                ConstantOpenField constOpenField = (ConstantOpenField) openField;
                spreadsheetCell.setValue(constOpenField.getValue());
            } catch (Exception e) {
                String message = "Cannot parse cell value.";
                spreadsheetBindingContext.addError(SyntaxNodeExceptionUtils.createError(message, e, null, source));
            }
        } else {
            Class<?> instanceClass = type.getInstanceClass();
            if (instanceClass == null) {
                String message = MessageUtils.getTypeDefinedErrorMessage(type.getName());
                spreadsheetBindingContext.addError(SyntaxNodeExceptionUtils.createError(message, source));
            }

            try {
                IBindingContext bindingContext = getColumnContext(columnIndex, rowIndex, rowBindingContext);
                ICell theCellValue = cell.getCell(0, 0);
                Object result = null;
                if (String.class == instanceClass) {
                    result = String2DataConvertorFactory.parse(instanceClass, code, bindingContext);
                } else {
                    if (theCellValue.hasNativeType()) {
                        result = RuleRowHelper.loadNativeValue(theCellValue, type);
                    }
                    if (result == null) {
                        result = String2DataConvertorFactory.parse(instanceClass, code, bindingContext);
                    }
                }

                if (bindingContext.isExecutionMode() && result instanceof IMetaHolder) {
                    IMetaInfo meta = new ValueMetaInfo(name, null, source);
                    ((IMetaHolder) result).setMetaInfo(meta);
                }

                IOpenCast openCast = bindingContext.getCast(JavaOpenClass.getOpenClass(instanceClass), type);
                spreadsheetCell.setValue(openCast.convert(result));
            } catch (Exception t) {
                String message = String.format("Cannot parse cell value '%s' to the necessary type.", code);
                spreadsheetBindingContext.addError(SyntaxNodeExceptionUtils.createError(message, t, null, source));
            }
        }
    }

    /**
     * Creates a field from the spreadsheet cell and add it to the spreadsheetType
     */
    private void addSpreadsheetFields(SpreadsheetOpenClass spreadsheetType, int rowIndex, int columnIndex) {
        SpreadsheetHeaderDefinition columnHeaders = this.columnHeaders.get(columnIndex);
        SpreadsheetHeaderDefinition rowHeaders = this.rowHeaders.get(rowIndex);

        if (columnHeaders == null || rowHeaders == null) {
            return;
        }

        boolean oneColumnSpreadsheet = this.columnHeaders.size() == 1;
        boolean oneRowSpreadsheet = this.rowHeaders.size() == 1;

        SymbolicTypeDefinition columnDefinition = columnHeaders.getDefinition();
        SymbolicTypeDefinition rowDefinition = rowHeaders.getDefinition();

        // get column name from the column definition
        String columnName = columnDefinition.getName().getIdentifier();

        // get row name from the row definition
        String rowName = rowDefinition.getName().getIdentifier();

        // create name of the field
        String fieldName = getSpreadsheetCellFieldName(columnName, rowName);

        SpreadsheetCell spreadsheetCell = cells[rowIndex][columnIndex];
        // create spreadsheet cell field
        createSpreadsheetCellField(spreadsheetType, spreadsheetCell, fieldName, SpreadsheetCellRefType.ROW_AND_COLUMN);

        if (oneColumnSpreadsheet) {
            // add simplified field name
            String simplifiedFieldName = getSpreadsheetCellSimplifiedFieldName(rowName);
            IOpenField field1 = spreadsheetType.getField(simplifiedFieldName);
            if (field1 == null) {
                createSpreadsheetCellField(spreadsheetType,
                    spreadsheetCell,
                    simplifiedFieldName,
                    SpreadsheetCellRefType.SINGLE_COLUMN);
            }
        } else if (oneRowSpreadsheet) {
            // add simplified field name
            String simplifiedFieldName = getSpreadsheetCellSimplifiedFieldName(columnName);
            IOpenField field1 = spreadsheetType.getField(simplifiedFieldName);
            if (field1 == null || field1 instanceof SpreadsheetCellField && ((SpreadsheetCellField) field1)
                .isLastColumnRef()) {
                createSpreadsheetCellField(spreadsheetType,
                    spreadsheetCell,
                    simplifiedFieldName,
                    SpreadsheetCellRefType.SINGLE_ROW);
            }
        }
    }

    private String getSpreadsheetCellSimplifiedFieldName(String rowName) {
        return (DOLLAR_SIGN + rowName).intern();
    }

    /**
     * Gets the name of the spreadsheet cell field. <br>
     * Is represented as {@link #DOLLAR_SIGN}columnName{@link #DOLLAR_SIGN} rowName, e.g. $Value$Final
     *
     * @param columnName name of cell column
     * @param rowName name of the row column
     * @return {@link #DOLLAR_SIGN}columnName{@link #DOLLAR_SIGN}rowName, e.g. $Value$Final
     */
    public static String getSpreadsheetCellFieldName(String columnName, String rowName) {
        return (DOLLAR_SIGN + columnName + DOLLAR_SIGN + rowName).intern();
    }

    private SpreadsheetCell buildCell(int rowIndex, int columnIndex, boolean autoType) {
        ILogicalTable cell = LogicalTableHelper.mergeBounds(
            rowNamesTable.getRow(rowIndex),
            columnNamesTable.getColumn(columnIndex));
        ICell sourceCell = cell.getSource().getCell(0, 0);

        String cellCode = sourceCell.getStringValue();

        IOpenField openField = null;

        SpreadsheetCellType spreadsheetCellType;
        if (cellCode == null || cellCode.isEmpty() || columnHeaders.get(columnIndex) == null || rowHeaders
            .get(rowIndex) == null) {
            spreadsheetCellType = SpreadsheetCellType.EMPTY;
        } else if (SpreadsheetExpressionMarker.isFormula(cellCode)) {
            spreadsheetCellType = SpreadsheetCellType.METHOD;
        } else {
            spreadsheetCellType = SpreadsheetCellType.VALUE;
            openField = RuleRowHelper.findConstantField(spreadsheetBindingContext, cellCode);
            if (openField != null) {
                spreadsheetCellType = SpreadsheetCellType.CONSTANT;
            }
        }

        SpreadsheetCell spreadsheetCell;
        ICell sourceCellForExecutionMode = spreadsheetBindingContext.isExecutionMode() ? null : sourceCell;
        spreadsheetCell = new SpreadsheetCell(rowIndex, columnIndex, sourceCellForExecutionMode, spreadsheetCellType);

        SpreadsheetHeaderDefinition columnHeader = columnHeaders.get(columnIndex);
        SpreadsheetHeaderDefinition rowHeader = rowHeaders.get(rowIndex);
        IOpenClass cellType;
        if (openField != null) {
            cellType = openField.getType();
        } else if (columnHeader != null && columnHeader.getType() != null) {
            cellType = columnHeader.getType();
        } else if (rowHeader != null && rowHeader.getType() != null) {
            cellType = rowHeader.getType();
        } else {

            // Try to derive cell type as double.
            //
            try {
                // Try to parse cell value.
                // If parse process will be finished with success then return
                // double type else string type.
                //
                if (autoType) {
                    if (SpreadsheetExpressionMarker.isFormula(cellCode)) {
                        cellType = null;
                    } else if (cellCode != null) {
                        Object objectValue = sourceCell.getObjectValue();
                        if (objectValue instanceof String) {
                            String2DataConvertorFactory.getConvertor(Double.class).parse(cellCode, null);
                            cellType = JavaOpenClass.getOpenClass(Double.class);
                        } else {
                            cellType = JavaOpenClass.getOpenClass(objectValue.getClass());
                        }
                    } else {
                        cellType = NullOpenClass.the;
                    }
                } else {
                    if (!SpreadsheetExpressionMarker.isFormula(cellCode)) {
                        String2DataConvertorFactory.getConvertor(Double.class).parse(cellCode, null);
                    }
                    cellType = JavaOpenClass.getOpenClass(Double.class);
                }
            } catch (Exception t) {
                cellType = JavaOpenClass.getOpenClass(String.class);
            }
        }
        spreadsheetCell.setType(cellType);

        return spreadsheetCell;
    }

    private IBindingContext getRowContext(int rowIndex) {
        IBindingContext rowContext = rowContexts.get(rowIndex);

        if (rowContext == null) {
            rowContext = makeRowContext(rowIndex);
            rowContexts.put(rowIndex, rowContext);
        }

        return rowContext;
    }

    private SpreadsheetContext getColumnContext(int columnIndex, int rowIndex, IBindingContext rowBindingContext) {
        Map<Integer, SpreadsheetContext> contexts = spreadsheetResultContexts.computeIfAbsent(columnIndex,
            e -> new HashMap<>());
        return contexts.computeIfAbsent(rowIndex, e -> makeSpreadsheetResultContext(columnIndex, rowBindingContext));
    }

    private SpreadsheetContext makeSpreadsheetResultContext(int columnIndex, IBindingContext rowBindingContext) {
        SpreadsheetOpenClass columnOpenClass = colComponentOpenClasses.computeIfAbsent(columnIndex,
            e -> makeColumnComponentOpenClass(columnIndex));
        return new SpreadsheetContext(rowBindingContext, columnOpenClass, xlsModuleOpenClass);
    }

    private SpreadsheetOpenClass makeColumnComponentOpenClass(int columnIndex) {
        // create name for the column open class
        String columnOpenClassName = String.format("%sColType%d", spreadsheetHeader.getName(), columnIndex);

        SpreadsheetOpenClass columnOpenClass = new SpreadsheetOpenClass(columnOpenClassName, bindingContext.getOpenL());

        for (int rowIndex = 0; rowIndex < cells.length; rowIndex++) {

            SpreadsheetHeaderDefinition headerDefinition = rowHeaders.get(rowIndex);

            proc(rowIndex, columnOpenClass, columnIndex, headerDefinition);
        }
        return columnOpenClass;
    }

    private IBindingContext makeRowContext(int rowIndex) {

        /* create name for the row open class */
        String rowOpenClassName = String.format("%sRowType%d", spreadsheetHeader.getName(), rowIndex);

        // create row open class for current row
        SpreadsheetOpenClass rowOpenClass = new SpreadsheetOpenClass(rowOpenClassName, bindingContext.getOpenL());

        // get the width of the whole spreadsheet
        int width = cells[0].length;

        // create for each column in row its field
        for (int columnIndex = 0; columnIndex < width; columnIndex++) {

            SpreadsheetHeaderDefinition columnHeader = columnHeaders.get(columnIndex);

            proc(rowIndex, rowOpenClass, columnIndex, columnHeader);
        }

        /* create row binding context */
        return new SpreadsheetContext(spreadsheetBindingContext, rowOpenClass, xlsModuleOpenClass);
    }

    private void proc(int rowIndex,
            ComponentOpenClass rowOpenClass,
            int columnIndex,
            SpreadsheetHeaderDefinition columnHeader) {
        if (columnHeader == null) {
            return;
        }

        SpreadsheetCell cell = cells[rowIndex][columnIndex];

        SymbolicTypeDefinition typeDefinition = columnHeader.getDefinition();
        String fieldName = (DOLLAR_SIGN + typeDefinition.getName().getIdentifier()).intern();
        createSpreadsheetCellField(rowOpenClass, cell, fieldName, SpreadsheetCellRefType.LOCAL);
    }

    private void createSpreadsheetCellField(ComponentOpenClass rowOpenClass,
            SpreadsheetCell cell,
            String fieldName,
            SpreadsheetCellRefType spreadsheetCellRefType) {
        SpreadsheetStructureBuilderHolder structureBuilderContainer = getSpreadsheetStructureBuilderHolder();
        SpreadsheetCellField field;
        if (cell.getSpreadsheetCellType() == SpreadsheetCellType.METHOD) {
            field = new SpreadsheetCellField(structureBuilderContainer,
                rowOpenClass,
                fieldName,
                cell,
                spreadsheetCellRefType);
        } else {
            field = new SpreadsheetCellField.ConstSpreadsheetCellField(structureBuilderContainer,
                rowOpenClass,
                fieldName,
                cell);
        }
        rowOpenClass.addField(field);
    }

    /**
     * tableSyntaxNode of the spreadsheet
     **/
    private final TableSyntaxNode tableSyntaxNode;

    /**
     * binding context for indicating execution mode
     **/
    private final IBindingContext bindingContext;

    private ReturnSpreadsheetHeaderDefinition returnHeaderDefinition;

    private final Map<Integer, SpreadsheetHeaderDefinition> rowHeaders = new HashMap<>();
    private final Map<Integer, SpreadsheetHeaderDefinition> columnHeaders = new HashMap<>();
    private final BidiMap<String, SpreadsheetHeaderDefinition> headerDefinitions = new DualHashBidiMap<>();

    private static String removeWrongSymbols(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.length() > 0) {
            s = s.replaceAll("\\s+", "_"); // Replace whitespaces
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                if (Character.isJavaIdentifierPart(s.charAt(i))) {
                    sb.append(s.charAt(i));
                }
            }
            s = sb.toString();
            if (JavaKeywordUtils.isJavaKeyword(s) || s.length() > 0 && !Character.isJavaIdentifierStart(s.charAt(0))) {
                s = "_" + s;
            }
        }
        return s;
    }

    public String[] getRowNamesForResultModel() {
        final long rowsWithAsteriskCount = rowHeaders.entrySet()
                .stream()
                .filter(e -> e.getValue().getDefinition().isAsteriskPresented())
                .count();
        String[] ret;
        if (rowsWithAsteriskCount > 0) {
            ret = buildArrayForHeaders(rowHeaders,
                    this.getHeight(),
                    e -> e.getDefinition().isAsteriskPresented());
        } else {
            ret = buildArrayForHeaders(rowHeaders,
                    this.getHeight(),
                    e -> !e.getDefinition().isTildePresented());
        }
        for (int i = 0; i < ret.length; i++) {
            ret[i] = removeWrongSymbols(ret[i]);
        }
        return ret;
    }

    public String[] getColumnNamesForResultModel() {
        final long columnsWithAsteriskCount = columnHeaders.entrySet()
                .stream()
                .filter(e -> e.getValue().getDefinition().isAsteriskPresented())
                .count();
        String[] ret;
        if (columnsWithAsteriskCount > 0) {
            ret = buildArrayForHeaders(columnHeaders,
                    this.getWidth(),
                    e -> e.getDefinition().isAsteriskPresented());
        } else {
            ret = buildArrayForHeaders(columnHeaders,
                    this.getWidth(),
                    e -> !e.getDefinition().isTildePresented());
        }

        for (int i = 0; i < ret.length; i++) {
            ret[i] = removeWrongSymbols(ret[i]);
        }

        return ret;
    }

    public String[] getRowNames() {
        return buildArrayForHeaders(rowHeaders, this.getHeight(), e -> true);
    }

    public String[] getColumnNames() {
        return buildArrayForHeaders(columnHeaders, this.getWidth(), e -> true);
    }

    private String[] buildArrayForHeaders(Map<Integer, SpreadsheetHeaderDefinition> headers,
                                          int size,
                                          Predicate<SpreadsheetHeaderDefinition> predicate) {
        String[] ret = new String[size];
        for (Map.Entry<Integer, SpreadsheetHeaderDefinition> x : headers.entrySet()) {
            int k = x.getKey();
            if (predicate.test(x.getValue())) {
                ret[k] = x.getValue().getDefinitionName();
            }
        }
        return ret;
    }

    public IResultBuilder buildResultBuilder(Spreadsheet spreadsheet, IBindingContext bindingContext) {
        IResultBuilder resultBuilder = null;
        try {
            resultBuilder = getResultBuilderInternal(spreadsheet, bindingContext);
        } catch (SyntaxNodeException e) {
            this.bindingContext.addError(e);
        }
        return resultBuilder;
    }

    private void addRowHeaders() {
        String[] rowNames = this.getRowNames2();
        for (int i = 0; i < rowNames.length; i++) {
            if (rowNames[i] != null) {
                IGridTable rowNameForHeader = rowNamesTable
                        .getRow(i)
                        .getColumn(0)
                        .getSource();
                IOpenSourceCodeModule source = new GridCellSourceCodeModule(rowNameForHeader, bindingContext);
                parseHeader(source, i, true);
            }
        }
    }

    private void addColumnHeaders() {
        String[] columnNames = this.getColumnNames2();
        for (int i = 0; i < columnNames.length; i++) {
            if (columnNames[i] != null) {
                IGridTable columnNameForHeader = columnNamesTable
                        .getColumn(i)
                        .getRow(0)
                        .getSource();
                GridCellSourceCodeModule source = new GridCellSourceCodeModule(columnNameForHeader, bindingContext);
                parseHeader(source, i, false);
            }
        }
    }

    private void parseHeader(IOpenSourceCodeModule source, int index, boolean row) {
        try {
            SymbolicTypeDefinition parsed = parseHeaderElement(source);
            IdentifierNode name = parsed.getName();
            String headerName = name.getIdentifier();

            SpreadsheetHeaderDefinition h1 = headerDefinitions.get(headerName);
            if (h1 != null) {
                SyntaxNodeException error = SyntaxNodeExceptionUtils.createError("The header definition is duplicated.",
                        name);
                bindingContext.addError(error);
                throw new DuplicatedVarException(null, headerName);
            } else {
                SpreadsheetHeaderDefinition header;
                if (row) {
                    header = rowHeaders.computeIfAbsent(index, e -> new SpreadsheetHeaderDefinition(parsed, index, -1));
                } else {
                    header = columnHeaders.computeIfAbsent(index,
                            e -> new SpreadsheetHeaderDefinition(parsed, -1, index));
                }
                headerDefinitions.put(headerName, header);
            }
        } catch (SyntaxNodeException error) {
            bindingContext.addError(error);
        }
    }

    private IdentifierNode removeSignInTheEnd(IdentifierNode identifierNode, String sign) {
        String s = org.apache.commons.lang3.StringUtils.stripEnd(identifierNode.getIdentifier(), null);
        int d = s.lastIndexOf(sign);
        if (d != s.length() - 1) {
            throw new IllegalStateException(String.format("'%s' symbols is not found.", sign));
        }
        String v = org.apache.commons.lang3.StringUtils.stripEnd(identifierNode.getIdentifier().substring(0, d), null);
        int delta = identifierNode.getIdentifier().length() - v.length();

        IPosition end = new AbsolutePosition(-delta + identifierNode.getLocation()
                .getEnd()
                .getAbsolutePosition(new TextInfo(identifierNode.getIdentifier())));
        ILocation location = new TextInterval(identifierNode.getLocation().getStart(), end);
        return new IdentifierNode(identifierNode.getType(), location, v, identifierNode.getModule());
    }

    private SymbolicTypeDefinition parseHeaderElement(IOpenSourceCodeModule source) throws SyntaxNodeException {
        IdentifierNode[] nodes;

        try {
            nodes = Tokenizer.tokenize(source, SpreadsheetSymbols.TYPE_DELIMITER.toString());
        } catch (OpenLCompilationException e) {
            throw SyntaxNodeExceptionUtils.createError("Cannot parse header.", source);
        }

        if (nodes.length == 0) {
            throw SyntaxNodeExceptionUtils.createError("Cannot parse header.", source);
        }

        IdentifierNode headerNameNode = nodes[0];
        boolean endsWithAsterisk = false;
        if ((nodes.length == 1 || nodes.length == 2) && nodes[0].getIdentifier()
                .endsWith(SpreadsheetSymbols.ASTERISK.toString())) {
            headerNameNode = removeSignInTheEnd(nodes[0], SpreadsheetSymbols.ASTERISK.toString());
            endsWithAsterisk = true;
        }
        boolean endsWithTilde = false;
        if ((nodes.length == 1 || nodes.length == 2) && nodes[0].getIdentifier()
                .endsWith(SpreadsheetSymbols.TILDE.toString())) {
            headerNameNode = removeSignInTheEnd(nodes[0], SpreadsheetSymbols.TILDE.toString());
            endsWithTilde = true;
        }
        switch (nodes.length) {
            case 1:
                return new SymbolicTypeDefinition(headerNameNode, null, endsWithAsterisk, endsWithTilde, source);
            case 2:
                return new SymbolicTypeDefinition(headerNameNode, nodes[1], endsWithAsterisk, endsWithTilde, source);
            default:
                String message = String.format("Valid header format: name [%s type].",
                        SpreadsheetSymbols.TYPE_DELIMITER);
                throw SyntaxNodeExceptionUtils.createError(message, nodes[2]);
        }
    }

    private void buildHeaderDefinitions() {
        for (SpreadsheetHeaderDefinition headerDefinition : headerDefinitions.values()) {

            IOpenClass headerType = null;
            SymbolicTypeDefinition symbolicTypeDefinition = headerDefinition.getDefinition();
            IdentifierNode typeIdentifierNode = symbolicTypeDefinition.getType();
            if (typeIdentifierNode != null) {
                String typeIdentifier = typeIdentifierNode.getOriginalText();
                headerType = OpenLManager.makeType(bindingContext.getOpenL(),
                        typeIdentifier,
                        symbolicTypeDefinition.getSource(),
                        bindingContext);
            }

            if (headerType != null) {
                headerDefinition.setType(headerType);
            }

            if (!bindingContext.isExecutionMode() && tableSyntaxNode
                    .getMetaInfoReader() instanceof SpreadsheetMetaInfoReader) {
                SpreadsheetMetaInfoReader metaInfoReader = (SpreadsheetMetaInfoReader) tableSyntaxNode
                        .getMetaInfoReader();
                List<NodeUsage> nodeUsages = new ArrayList<>();
                ILogicalTable cell;
                if (headerDefinition.getRow() >= 0) {
                    cell = rowNamesTable.getRow(headerDefinition.getRow());
                } else {
                    cell = columnNamesTable.getColumn(headerDefinition.getColumn());
                }
                if (headerDefinition.getDefinition().isAsteriskPresented()) {
                    String s = removeWrongSymbols(headerDefinition.getDefinitionName());
                    if (org.apache.commons.lang3.StringUtils.isEmpty(s)) {
                        s = "Empty string";
                    }
                    String stringValue = cell.getCell(0, 0).getStringValue();
                    int d = stringValue.lastIndexOf(SpreadsheetSymbols.ASTERISK.toString());
                    SimpleNodeUsage nodeUsage = new SimpleNodeUsage(0, d, s, null, NodeType.OTHER);
                    nodeUsages.add(nodeUsage);
                }
                if (headerType != null) {
                    IdentifierNode identifier = cutTypeIdentifier(typeIdentifierNode);
                    if (identifier != null) {
                        IOpenClass type = headerType;
                        while (type.getMetaInfo() == null && type.isArray()) {
                            type = type.getComponentClass();
                        }
                        IMetaInfo typeMeta = type.getMetaInfo();
                        if (typeMeta != null) {
                            SimpleNodeUsage nodeUsage = new SimpleNodeUsage(identifier,
                                    typeMeta.getDisplayName(INamedThing.SHORT),
                                    typeMeta.getSourceUrl(),
                                    NodeType.DATATYPE);
                            nodeUsages.add(nodeUsage);
                        }
                    }
                }
                if (!nodeUsages.isEmpty()) {
                    CellMetaInfo cellMetaInfo = new CellMetaInfo(JavaOpenClass.STRING, false, nodeUsages);
                    ICell c = cell.getCell(0, 0);
                    metaInfoReader.addHeaderMetaInfo(c.getAbsoluteRow(), c.getAbsoluteColumn(), cellMetaInfo);
                }
            }
        }
    }

    /**
     * Cut a type identifier from a type identifier containing array symbols and whitespace.
     *
     * @param typeIdentifierNode identifier with additional info
     * @return cleaned type identifier
     */
    private IdentifierNode cutTypeIdentifier(IdentifierNode typeIdentifierNode) {
        try {
            IdentifierNode[] variableAndType = Tokenizer.tokenize(typeIdentifierNode.getModule(),
                    SpreadsheetSymbols.TYPE_DELIMITER.toString());
            if (variableAndType.length > 1) {
                IdentifierNode[] nodes = Tokenizer
                        .tokenize(typeIdentifierNode.getModule(), " []\n\r", variableAndType[1].getLocation());
                if (nodes.length > 0) {
                    return nodes[0];
                }
            }
        } catch (OpenLCompilationException e) {
            SyntaxNodeException error = SyntaxNodeExceptionUtils.createError("Cannot parse header.",
                    typeIdentifierNode);
            bindingContext.addError(error);
        }

        return null;
    }

    private void buildReturnCells(IOpenClass spreadsheetHeaderType) {
        SpreadsheetHeaderDefinition headerDefinition = headerDefinitions.get(SpreadsheetSymbols.RETURN_NAME.toString());

        if (bindingContext.findType(ISyntaxConstants.THIS_NAMESPACE, SpreadsheetResult.class.getSimpleName())
                .equals(spreadsheetHeaderType) && headerDefinition == null) {
            return;
        }

        if (headerDefinition == null) {
            for (SpreadsheetHeaderDefinition spreadsheetHeaderDefinition : headerDefinitions.values()) {
                if (headerDefinition == null || headerDefinition.getRow() < spreadsheetHeaderDefinition.getRow()) {
                    headerDefinition = spreadsheetHeaderDefinition;
                }
            }
        }
        if (headerDefinition == null) {
            throw new IllegalStateException("Expected non-null headerDefinition");
        }

        if (Boolean.FALSE
                .equals(tableSyntaxNode.getTableProperties().getAutoType()) && headerDefinition.getType() == null) {
            headerDefinition.setType(spreadsheetHeaderType);
        } else if (spreadsheetHeaderType
                .getAggregateInfo() == null || spreadsheetHeaderType.getAggregateInfo() != null && spreadsheetHeaderType
                .getAggregateInfo()
                .getComponentType(spreadsheetHeaderType) == null) {
            int nonEmptyCellsCount = getNonEmptyCellsCount(headerDefinition);
            if (nonEmptyCellsCount == 1) {
                headerDefinition.setType(spreadsheetHeaderType);
            }
        }

        String key = headerDefinitions.getKey(headerDefinition);
        returnHeaderDefinition = new ReturnSpreadsheetHeaderDefinition(headerDefinition);
        headerDefinitions.replace(key, returnHeaderDefinition);
    }

    private int getNonEmptyCellsCount(SpreadsheetHeaderDefinition headerDefinition) {
        int fromRow = 0;
        int toRow = this.getHeight();

        int fromColumn = 0;
        int toColumn = this.getWidth();

        if (headerDefinition.isRow()) {
            fromRow = headerDefinition.getRow();
            toRow = fromRow + 1;
        } else {
            fromColumn = headerDefinition.getColumn();
            toColumn = fromColumn + 1;
        }

        int nonEmptyCellsCount = 0;

        for (int columnIndex = fromColumn; columnIndex < toColumn; columnIndex++) {
            for (int rowIndex = fromRow; rowIndex < toRow; rowIndex++) {

                ILogicalTable cell = LogicalTableHelper.mergeBounds(
                        rowNamesTable.getRow(rowIndex),
                        columnNamesTable.getColumn(columnIndex));
                String value = cell.getSource().getCell(0, 0).getStringValue();
                boolean isFormula = Optional.ofNullable(org.apache.commons.lang3.StringUtils.trimToNull(value))
                        .map(SpreadsheetExpressionMarker::isFormula)
                        .orElse(false);
                if (!org.apache.commons.lang3.StringUtils.isBlank(value) && !isFormula) {
                    nonEmptyCellsCount += 1;
                }
            }
        }
        return nonEmptyCellsCount;
    }

    public boolean isExistsReturnHeader() {
        return returnHeaderDefinition != null;
    }

    public boolean isReturnCell(SpreadsheetCell cell) {
        return returnHeaderDefinition != null && returnHeaderDefinition.isReturnCell(cell);
    }

    private IResultBuilder getResultBuilderInternal(Spreadsheet spreadsheet,
                                                    IBindingContext bindingContext) throws SyntaxNodeException {

        if (OpenClassUtils.isVoid(spreadsheet.getHeader().getType())) {
            return new EmptyResultBuilder();
        }

        IResultBuilder resultBuilder;

        SymbolicTypeDefinition symbolicTypeDefinition = null;

        if (isExistsReturnHeader()) {
            String key = headerDefinitions.getKey(returnHeaderDefinition);
            symbolicTypeDefinition = returnHeaderDefinition.findDefinition(key);
        }

        if (!isExistsReturnHeader() && bindingContext
                .findType(ISyntaxConstants.THIS_NAMESPACE, SpreadsheetResult.class.getSimpleName())
                .equals(spreadsheet.getHeader().getType())) {
            resultBuilder = new SpreadsheetResultBuilder();
        } else {
            // real return type
            //
            List<SpreadsheetCell> returnSpreadsheetCells = new ArrayList<>();
            List<IOpenCast> casts = new ArrayList<>();
            List<SpreadsheetCell> returnSpreadsheetCellsAsArray = new ArrayList<>();
            List<IOpenCast> castsAsArray = new ArrayList<>();

            IOpenClass type = spreadsheet.getType();
            IAggregateInfo aggregateInfo = type.getAggregateInfo();
            IOpenClass componentType = aggregateInfo.getComponentType(type);
            boolean asArray = false;

            List<SpreadsheetCell> sprCells = new ArrayList<>();
            int n = returnHeaderDefinition.getRow();
            boolean byColumn = true;
            if (n < 0) {
                n = returnHeaderDefinition.getColumn();
                byColumn = false;
                for (int i = 0; i < spreadsheet.getCells().length; i++) {
                    sprCells.add(spreadsheet.getCells()[i][n]);
                }
            } else {
                sprCells.addAll(Arrays.asList(spreadsheet.getCells()[n]));
            }

            List<SpreadsheetCell> nonEmptySpreadsheetCells = new ArrayList<>();
            for (SpreadsheetCell cell : sprCells) {
                if (!cell.isEmpty()) {
                    nonEmptySpreadsheetCells.add(cell);
                    if (cell.getType() != null) {
                        IOpenCast cast = bindingContext.getCast(cell.getType(), type);
                        if (cast != null && cast.isImplicit() && !(cast instanceof IOneElementArrayCast)) {
                            returnSpreadsheetCells.add(cell);
                            casts.add(cast);
                        }

                        if (returnSpreadsheetCells.isEmpty() && componentType != null) {
                            cast = bindingContext.getCast(cell.getType(), componentType);
                            if (cast != null && cast.isImplicit() && !(cast instanceof IOneElementArrayCast)) {
                                returnSpreadsheetCellsAsArray.add(cell);
                                castsAsArray.add(cast);
                            }
                        }
                    }
                }
            }

            if (componentType != null && returnSpreadsheetCells.isEmpty()) {
                returnSpreadsheetCells = returnSpreadsheetCellsAsArray;
                returnHeaderDefinition.setType(componentType);
                casts = castsAsArray;
                asArray = true;
            } else {
                returnHeaderDefinition.setType(type);
            }

            SpreadsheetCell[] retCells = returnSpreadsheetCells.toArray(new SpreadsheetCell[]{});
            if (!returnSpreadsheetCells.isEmpty()) {
                if (asArray) {
                    returnHeaderDefinition.setReturnCells(byColumn, retCells);
                } else {
                    returnHeaderDefinition.setReturnCells(byColumn,
                            returnSpreadsheetCells.get(returnSpreadsheetCells.size() - 1));
                }
            } else {
                if (!nonEmptySpreadsheetCells.isEmpty()) {
                    if (asArray) {
                        returnHeaderDefinition.setReturnCells(byColumn,
                                nonEmptySpreadsheetCells.toArray(new SpreadsheetCell[]{}));
                    } else {
                        returnHeaderDefinition.setReturnCells(byColumn,
                                nonEmptySpreadsheetCells.get(nonEmptySpreadsheetCells.size() - 1));
                    }
                }
            }

            if (returnSpreadsheetCells.size() == 0) {
                IdentifierNode symbolicTypeDefinitionName = Optional.ofNullable(symbolicTypeDefinition)
                        .map(SymbolicTypeDefinition::getName)
                        .orElse(null);
                if (!nonEmptySpreadsheetCells.isEmpty()) {
                    SpreadsheetCell nonEmptySpreadsheetCell = nonEmptySpreadsheetCells
                            .get(nonEmptySpreadsheetCells.size() - 1);
                    if (nonEmptySpreadsheetCell.getType() != null) {
                        throw SyntaxNodeExceptionUtils.createError(
                                String.format("Cannot convert from '%s' to '%s'.",
                                        nonEmptySpreadsheetCell.getType().getName(),
                                        spreadsheet.getHeader().getType().getName()),
                                Optional.ofNullable(nonEmptySpreadsheetCell.getMethod())
                                        .filter(CompositeMethod.class::isInstance)
                                        .map(CompositeMethod.class::cast)
                                        .map(CompositeMethod::getMethodBodyBoundNode)
                                        .map(IBoundMethodNode::getSyntaxNode)
                                        .orElse(symbolicTypeDefinitionName));
                    } else {
                        return null;
                    }
                } else {
                    throw SyntaxNodeExceptionUtils.createError("There is no return expression cell.",
                            symbolicTypeDefinitionName);
                }
            } else {
                if (asArray) {
                    resultBuilder = new ArrayResultBuilder(retCells,
                            castsAsArray.toArray(new IOpenCast[]{}),
                            type,
                            isCalculateAllCellsInSpreadsheet(spreadsheet));
                } else {
                    resultBuilder = new ScalarResultBuilder(
                            returnSpreadsheetCells.get(returnSpreadsheetCells.size() - 1),
                            casts.get(casts.size() - 1),
                            isCalculateAllCellsInSpreadsheet(spreadsheet));
                }
            }
        }
        return resultBuilder;
    }

    private boolean isCalculateAllCellsInSpreadsheet(Spreadsheet spreadsheet) {
        return !Boolean.FALSE.equals(spreadsheet.getMethodProperties().getCalculateAllCells());
    }

    private String[] rowNames;
    private String[] columnNames;

    /**
     * table representing column section in the spreadsheet
     **/
    private final ILogicalTable columnNamesTable;

    /**
     * table representing row section in the spreadsheet
     **/
    private final ILogicalTable rowNamesTable;

    private int getWidth() {
        return columnNamesTable == null ? 0 : columnNamesTable.getWidth();
    }

    private int getHeight() {
        return rowNamesTable == null ? 0 : rowNamesTable.getHeight();
    }

    public String[] getRowNames2() {
        if (rowNames == null) {
            int height = getHeight();
            rowNames = new String[height];
            for (int row = 0; row < height; row++) {
                IGridTable nameCell = rowNamesTable.getRow(row).getColumn(0).getSource();
                rowNames[row] = nameCell.getCell(0, 0).getStringValue();
            }
        }
        return rowNames;
    }

    public String[] getColumnNames2() {
        if (columnNames == null) {
            int width = getWidth();
            columnNames = new String[width];
            for (int col = 0; col < width; col++) {
                IGridTable nameCell = columnNamesTable.getColumn(col).getRow(0).getSource();
                columnNames[col] = nameCell.getCell(0, 0).getStringValue();
            }
        }
        return columnNames;
    }
}
