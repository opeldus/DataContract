package de.opeldus.datacontract.etl;

import java.nio.charset.StandardCharsets;

/** A self-written DSX export for tests. Real exports stay out of the repository, they can contain connection details. */
final class SampleDsx {

    private SampleDsx() {}

    static byte[] bytes() {
        return TEXT.getBytes(StandardCharsets.UTF_8);
    }

    private static final String TEXT = """
BEGIN HEADER
   CharacterSet "CP1252"
   ExportingTool "IBM InfoSphere DataStage Export"
   ToolVersion "8"
   ServerName "BEISPIEL"
   ServerVersion "11.7"
END HEADER
BEGIN DSJOB
   Identifier "SEQ_KUNDEN_TAG"
   DateModified "2026-10-01"
   BEGIN DSRECORD
      Identifier "ROOT"
      OLEType "CJobDefn"
      Name "SEQ_KUNDEN_TAG"
      Category "\\\\Jobs\\\\Vertrieb"
      Description "Selbst geschriebenes Beispiel: tägliche Beladung der Kundendaten."
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S1"
      OLEType "CJSJobActivity"
      Name "Kunden_laden"
      Jobname "JOB_KUNDE_LADEN"
      OutputPins "V0S1P1"
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S1P1"
      OLEType "CJSActivityOutput"
      Name "ok"
      Partner "V0S2|V0S2P1"
      ConditionType "OK"
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S2"
      OLEType "CJSJobActivity"
      Name "Umsatz_verdichten"
      Jobname "JOB_UMSATZ_VERDICHTEN"
      InputPins "V0S2P1"
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S2P1"
      OLEType "CJSActivityInput"
      Name "ok"
      Partner "V0S1|V0S1P1"
   END DSRECORD
END DSJOB
BEGIN DSJOB
   Identifier "JOB_KUNDE_LADEN"
   BEGIN DSRECORD
      Identifier "ROOT"
      OLEType "CJobDefn"
      Name "JOB_KUNDE_LADEN"
      Category "\\\\Jobs\\\\Vertrieb"
      Description "Liest Kunden und Aufträge aus DB2 und schreibt nach STG."
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S1"
      OLEType "CCustomStage"
      Name "DB2_Kunde"
      StageType "DB2ConnectorPX"
      OutputPins "V0S1P1"
      BEGIN DSSUBRECORD
         Name "XMLProperties"
         Value =+=+=+=
<?xml version='1.0' encoding='UTF-16'?><Properties version='1.1'><Common><Context type='int'>1</Context></Common><Connection><Instance modified='1' type='string'><![CDATA[#DB2_INSTANZ#]]></Instance><Password modified='1' type='protectedstring'><![CDATA[streng-geheim]]></Password></Connection><Usage><SQL><SelectStatement collapsed='1' modified='1' type='string'><![CDATA[SELECT k.KUNDEN_NR, k.NAME, a.AUFTRAG_ID
FROM SALES.KUNDE k
LEFT JOIN SALES.AUFTRAG a ON a.KUNDEN_NR = k.KUNDEN_NR
WHERE k.GEAENDERT_AM >= '#AB_DATUM#']]></SelectStatement></SQL></Usage></Properties>
=+=+=+=
      END DSSUBRECORD
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S1P1"
      OLEType "CCustomOutput"
      Name "lnk_quelle"
      Partner "V0S2|V0S2P1"
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S2"
      OLEType "CTransformerStage"
      Name "TRF_bereinigen"
      InputPins "V0S2P1"
      OutputPins "V0S2P2"
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S2P1"
      OLEType "CTrxInput"
      Name "lnk_quelle"
      Partner "V0S1|V0S1P1"
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S2P2"
      OLEType "CTrxOutput"
      Name "lnk_ziel"
      Partner "V0S3|V0S3P1"
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S3"
      OLEType "CCustomStage"
      Name "SF_Staging"
      StageType "SnowflakeConnectorPX"
      InputPins "V0S3P1"
      BEGIN DSSUBRECORD
         Name "XMLProperties"
         Value =+=+=+=
<Properties><Usage><TableName modified='1' type='string'><![CDATA[DWH.STG.KUNDE]]></TableName></Usage></Properties>
=+=+=+=
      END DSSUBRECORD
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S3P1"
      OLEType "CCustomInput"
      Name "lnk_ziel"
      Partner "V0S2|V0S2P2"
   END DSRECORD
END DSJOB
BEGIN DSJOB
   Identifier "JOB_UMSATZ_VERDICHTEN"
   BEGIN DSRECORD
      Identifier "ROOT"
      OLEType "CJobDefn"
      Name "JOB_UMSATZ_VERDICHTEN"
      Category "\\\\Jobs\\\\Vertrieb"
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S1"
      OLEType "CCustomStage"
      Name "SF_MERGE_Mart"
      StageType "SnowflakeConnectorPX"
      BEGIN DSSUBRECORD
         Name "XMLProperties"
         Value =+=+=+=
<Properties><Usage><SQL><UserDefinedSQL><![CDATA[MERGE INTO DWH.MART.KUNDENUMSATZ z
USING (SELECT KUNDEN_NR, COUNT(*) ANZAHL FROM DWH.STG.KUNDE GROUP BY KUNDEN_NR) q
ON z.KUNDEN_NR = q.KUNDEN_NR
WHEN MATCHED THEN UPDATE SET z.ANZAHL = q.ANZAHL
WHEN NOT MATCHED THEN INSERT (KUNDEN_NR, ANZAHL) VALUES (q.KUNDEN_NR, q.ANZAHL)]]></UserDefinedSQL></SQL></Usage></Properties>
=+=+=+=
      END DSSUBRECORD
   END DSRECORD
   BEGIN DSRECORD
      Identifier "V0S2"
      OLEType "CContainerStage"
      Name "CTR_Pruefung"
   END DSRECORD
END DSJOB
""";
}
