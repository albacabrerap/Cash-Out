package com.checkout.backend.assistant.service;

import com.checkout.backend.assistant.classifier.Classification.Kind;
import com.checkout.backend.assistant.classifier.ClassificationInput;
import com.checkout.backend.assistant.classifier.UserRuleClassifier;
import com.checkout.backend.assistant.dto.ConfirmDraftRequest;
import com.checkout.backend.assistant.dto.ConfirmDraftResponse;
import com.checkout.backend.assistant.receipt.ScannedReceipt;
import com.checkout.backend.assistant.receipt.ScannedReceiptRepository;
import com.checkout.backend.exceptions.DuplicateResourceException;
import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.savings.expense.dto.ExpenseRequest;
import com.checkout.backend.savings.expense.repository.ExpenseRepository;
import com.checkout.backend.savings.expense.service.ExpenseService;
import com.checkout.backend.savings.income.dto.IncomeRequest;
import com.checkout.backend.savings.income.repository.IncomeRepository;
import com.checkout.backend.savings.income.service.IncomeService;
import com.checkout.backend.user.model.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guarda una tarjeta confirmada: crea el gasto o el ingreso con los servicios
 * de siempre (con sus reglas de saldo), marca la boleta como registrada y, si
 * el usuario corrigio la sugerencia, aprende una regla.
 *
 * Todo va en una transaccion: si la boleta ya estaba registrada, o si no hay
 * saldo para el gasto, no queda nada a medias. Dos confirmaciones simultaneas
 * de la misma boleta chocan contra el UNIQUE de la huella y solo una pasa.
 */
@Service
public class DraftConfirmationService {

    /** El origen de un ingreso es obligatorio; si la tarjeta no tiene descripcion, se usa este. */
    static final String DEFAULT_INCOME_SOURCE = "Registrado con el asistente";
    private static final int MAX_SOURCE = 100;

    private final ExpenseService expenses;
    private final IncomeService incomes;
    private final ExpenseRepository expenseRepository;
    private final IncomeRepository incomeRepository;
    private final ScannedReceiptRepository scannedReceipts;
    private final UserRuleClassifier rules;

    public DraftConfirmationService(ExpenseService expenses, IncomeService incomes, ExpenseRepository expenseRepository,
                                    IncomeRepository incomeRepository, ScannedReceiptRepository scannedReceipts,
                                    UserRuleClassifier rules) {
        this.expenses = expenses;
        this.incomes = incomes;
        this.expenseRepository = expenseRepository;
        this.incomeRepository = incomeRepository;
        this.scannedReceipts = scannedReceipts;
        this.rules = rules;
    }

    @Transactional
    public ConfirmDraftResponse confirm(User user, ConfirmDraftRequest request) {
        if (request.kind() == Kind.EXPENSE && request.category() == null) {
            throw new InvalidRequestException("Elige una categoría para el gasto.");
        }
        String description = request.description() == null || request.description().isBlank()
                ? null : request.description().strip();
        if (request.receiptRef() != null
                && scannedReceipts.findByUserIdAndFingerprint(user.getId(), request.receiptRef()).isPresent()) {
            throw new DuplicateResourceException("Ya registraste esta boleta.");
        }

        long id;
        ScannedReceipt receipt = new ScannedReceipt();
        if (request.kind() == Kind.EXPENSE) {
            id = expenses.create(user, new ExpenseRequest(request.category(), request.amount(), request.date(),
                    description)).getId();
            receipt.setExpense(expenseRepository.getReferenceById(id));
        } else {
            String source = description == null ? DEFAULT_INCOME_SOURCE
                    : description.length() > MAX_SOURCE ? description.substring(0, MAX_SOURCE) : description;
            id = incomes.create(user, new IncomeRequest(request.amount(), source, request.date(), description)).getId();
            receipt.setIncome(incomeRepository.getReferenceById(id));
        }

        if (request.receiptRef() != null) {
            receipt.setUserId(user.getId());
            receipt.setFingerprint(request.receiptRef());
            try {
                // flush aqui y no al final: si otra confirmacion gano la carrera, el error sale ahora y deshace el gasto.
                scannedReceipts.saveAndFlush(receipt);
            } catch (DataIntegrityViolationException e) {
                throw new DuplicateResourceException("Ya registraste esta boleta.");
            }
        }

        boolean learned = Boolean.TRUE.equals(request.learn()) && rules.learn(
                new ClassificationInput(user.getId(), description, null, null, request.ruc()),
                request.kind(), request.category());
        return new ConfirmDraftResponse(request.kind().name(), id, learned);
    }

}
