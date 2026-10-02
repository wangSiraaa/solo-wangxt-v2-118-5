package com.treasury.clearing.web;

import com.treasury.clearing.domain.*;
import com.treasury.clearing.service.BatchService;
import com.treasury.clearing.service.BatchService.SimulationRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class ClearingController {

    private final BatchService batchService;
    private final com.treasury.clearing.repo.LegalEntityRepository entityRepository;
    private final com.treasury.clearing.repo.NettingAgreementRepository agreementRepository;
    private final com.treasury.clearing.repo.ClaimRepository claimRepository;
    private final com.treasury.clearing.repo.FxRateRepository fxRateRepository;

    public ClearingController(BatchService batchService,
                              com.treasury.clearing.repo.LegalEntityRepository entityRepository,
                              com.treasury.clearing.repo.NettingAgreementRepository agreementRepository,
                              com.treasury.clearing.repo.ClaimRepository claimRepository,
                              com.treasury.clearing.repo.FxRateRepository fxRateRepository) {
        this.batchService = batchService;
        this.entityRepository = entityRepository;
        this.agreementRepository = agreementRepository;
        this.claimRepository = claimRepository;
        this.fxRateRepository = fxRateRepository;
    }

    // ---------------- master data ----------------

    @GetMapping("/entities")
    public List<Map<String, Object>> entities() {
        return entityRepository.findAll().stream()
                .sorted(Comparator.comparing(LegalEntity::getCode))
                .map(e -> Map.<String, Object>of(
                        "code", e.getCode(),
                        "name", e.getName(),
                        "functionalCurrency", nv(e.getFunctionalCurrency()),
                        "active", e.isActive()))
                .toList();
    }

    @GetMapping("/agreements")
    public List<Map<String, Object>> agreements() {
        return agreementRepository.findAll().stream()
                .sorted(Comparator.comparing(NettingAgreement::getCode))
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", a.getCode());
                    m.put("name", a.getName());
                    m.put("allowsNetting", a.isAllowsNetting());
                    m.put("crossCurrency", a.isCrossCurrency());
                    m.put("settlementCurrency", nv(a.getSettlementCurrency()));
                    m.put("roundingBearerCode", nv(a.getRoundingBearerCode()));
                    m.put("members", new TreeSet<>(a.getMembers()));
                    m.put("currencies", a.getCurrencies().isEmpty()
                            ? List.of() : new TreeSet<>(a.getCurrencies()));
                    m.put("effectiveFrom", a.getEffectiveFrom());
                    m.put("effectiveTo", nv(a.getEffectiveTo()));
                    m.put("fxAsOf", nv(a.getFxAsOf()));
                    m.put("fxMargin", nv(a.getFxMargin()));
                    return m;
                })
                .toList();
    }

    @GetMapping("/claims")
    public List<Map<String, Object>> claims(@RequestParam(required = false) String agreementCode,
                                            @RequestParam(required = false) Boolean openOnly) {
        List<Claim> claims = agreementCode != null
                ? claimRepository.findByAgreementCodeOrderByInvoiceNo(agreementCode)
                : claimRepository.findAll();
        return claims.stream()
                .filter(c -> !Boolean.TRUE.equals(openOnly) || c.getStatus() == ClaimStatus.OPEN)
                .sorted(Comparator.comparing(Claim::getAgreementCode)
                        .thenComparing(Claim::getInvoiceNo, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("invoiceNo", nv(c.getInvoiceNo()));
                    m.put("agreementCode", c.getAgreementCode());
                    m.put("debtorCode", c.getDebtorCode());
                    m.put("creditorCode", c.getCreditorCode());
                    m.put("amount", c.getAmount());
                    m.put("currency", c.getCurrency());
                    m.put("status", c.getStatus().name());
                    m.put("invoiceDate", c.getInvoiceDate());
                    m.put("dueDate", nv(c.getDueDate()));
                    m.put("description", nv(c.getDescription()));
                    m.put("offsetBatchId", nv(c.getOffsetBatchId()));
                    return m;
                })
                .toList();
    }

    @GetMapping("/fx-rates")
    public List<Map<String, Object>> fxRates() {
        return fxRateRepository.findAll().stream()
                .sorted(Comparator.comparing(FxRate::getBaseCurrency)
                        .thenComparing(FxRate::getQuoteCurrency)
                        .thenComparing(FxRate::getAsOf))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("baseCurrency", r.getBaseCurrency());
                    m.put("quoteCurrency", r.getQuoteCurrency());
                    m.put("asOf", r.getAsOf());
                    m.put("rate", r.getRate());
                    m.put("source", nv(r.getSource()));
                    return m;
                })
                .toList();
    }

    // ---------------- batches ----------------

    public record SimulateRequest(LocalDate valuationDate,
                                  List<String> agreementCodes,
                                  String note) {
    }

    @PostMapping("/batches/simulate")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> simulate(@Valid @RequestBody SimulateRequest request) {
        NettingBatch batch = batchService.simulate(new SimulationRequest(
                request.valuationDate(), request.agreementCodes(), request.note()));
        return toBatchJson(batchService.getBatch(batch.getId()));
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> listBatches() {
        return batchService.listBatches().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("valuationDate", b.getValuationDate());
            m.put("status", b.getStatus().name());
            m.put("incomingClaimCount", b.getIncomingClaimCount());
            m.put("includedClaimCount", b.getIncludedClaimCount());
            m.put("excludedClaimCount", b.getExcludedClaimCount());
            m.put("originalLegCount", b.getOriginalLegCount());
            m.put("nettedLegCount", b.getNettedLegCount());
            m.put("grossAmount", b.getGrossAmount());
            m.put("netAmount", b.getNetAmount());
            m.put("createdAt", b.getCreatedAt());
            return m;
        }).toList();
    }

    @GetMapping("/batches/{id}")
    public Map<String, Object> getBatch(@PathVariable String id) {
        return toBatchJson(batchService.getBatch(id));
    }

    @PostMapping("/batches/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable String id) {
        batchService.confirm(id);
        return toBatchJson(batchService.getBatch(id));
    }

    @PostMapping("/batches/{id}/pay-simulated")
    public Map<String, Object> paySimulated(@PathVariable String id) {
        batchService.markPaidSimulated(id);
        return toBatchJson(batchService.getBatch(id));
    }

    // ---------------- projection ----------------

    private Map<String, Object> toBatchJson(NettingBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("valuationDate", b.getValuationDate());
        m.put("status", b.getStatus().name());
        m.put("incomingClaimCount", b.getIncomingClaimCount());
        m.put("includedClaimCount", b.getIncludedClaimCount());
        m.put("excludedClaimCount", b.getExcludedClaimCount());
        m.put("originalLegCount", b.getOriginalLegCount());
        m.put("nettedLegCount", b.getNettedLegCount());
        m.put("grossAmount", b.getGrossAmount().setScale(2, java.math.RoundingMode.HALF_UP));
        m.put("netAmount", b.getNetAmount().setScale(2, java.math.RoundingMode.HALF_UP));
        m.put("createdAt", b.getCreatedAt());
        m.put("confirmedAt", nv(b.getConfirmedAt()));
        m.put("paidSimulatedAt", nv(b.getPaidSimulatedAt()));
        m.put("note", nv(b.getNote()));

        List<Map<String, Object>> groups = new ArrayList<>();
        for (BatchGroup g : b.getGroups()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("id", g.getId());
            gm.put("agreementCode", g.getAgreementCode());
            gm.put("agreementName", g.getAgreementName());
            gm.put("settlementCurrency", g.getSettlementCurrency());
            gm.put("crossCurrency", g.isCrossCurrency());
            gm.put("passThrough", g.isPassThrough());
            gm.put("originalLegCount", g.getOriginalLegCount());
            gm.put("nettedLegCount", g.getNettedLegCount());
            gm.put("grossAmount", g.getGrossAmount().setScale(2, java.math.RoundingMode.HALF_UP));
            gm.put("netAmount", g.getNetAmount().setScale(2, java.math.RoundingMode.HALF_UP));
            gm.put("roundingDiffTotal", g.getRoundingDiffTotal());
            gm.put("roundingResidual", g.getRoundingResidual());
            gm.put("roundingBearerCode", nv(g.getRoundingBearerCode()));

            List<Map<String, Object>> legs = new ArrayList<>();
            for (BatchLeg leg : g.getLegs()) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("id", leg.getId());
                lm.put("payerCode", leg.getPayerCode());
                lm.put("receiverCode", leg.getReceiverCode());
                lm.put("amount", leg.getAmount());
                lm.put("settlementCurrency", leg.getSettlementCurrency());
                lm.put("isOriginal", leg.isOriginal());
                lm.put("isMemo", leg.getAmount().signum() == 0 && !leg.isOriginal());
                lm.put("amountExact", nv(leg.getAmountExact()));
                lm.put("residualAdjustment", leg.getResidualAdjustment());
                lm.put("seqNo", leg.getSeqNo());
                lm.put("paidSimulatedAt", nv(leg.getPaidSimulatedAt()));
                lm.put("items", leg.getItems().stream().map(this::toItemJson).toList());
                legs.add(lm);
            }
            gm.put("legs", legs);

            gm.put("exclusions", g.getExclusions().stream().map(ex -> {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("id", ex.getId());
                em.put("claimId", ex.getClaimId());
                em.put("invoiceNo", nv(ex.getInvoiceNo()));
                em.put("reasonCode", ex.getReasonCode());
                em.put("reasonDetail", nv(ex.getReasonDetail()));
                return em;
            }).toList());
            groups.add(gm);
        }
        m.put("groups", groups);
        return m;
    }

    private Map<String, Object> toItemJson(LegItem it) {
        Map<String, Object> im = new LinkedHashMap<>();
        im.put("id", it.getId());
        im.put("claimId", it.getClaimId());
        im.put("invoiceNo", nv(it.getInvoiceNo()));
        im.put("debtorCode", it.getDebtorCode());
        im.put("creditorCode", it.getCreditorCode());
        im.put("side", it.getSide().name());
        im.put("originalAmount", it.getOriginalAmount());
        im.put("originalCurrency", it.getOriginalCurrency());
        im.put("fxRate", nv(it.getFxRate()));
        im.put("fxAsOf", nv(it.getFxAsOf()));
        im.put("fxSource", nv(it.getFxSource()));
        im.put("fxInverted", it.isFxInverted());
        im.put("convertedExact", it.getConvertedExact());
        im.put("convertedBooked", it.getConvertedBooked());
        im.put("roundingDiff", it.getRoundingDiff());
        im.put("roundingBearerCode", nv(it.getRoundingBearerCode()));
        return im;
    }

    private static Object nv(Object o) {
        return o == null ? "" : o;
    }
}
