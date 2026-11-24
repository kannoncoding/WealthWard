# Ciclo mensual de WealthWard

El período es el mes calendario de Costa Rica. Al abrir la app o cambiar de fecha con la app abierta, WealthWard prepara el mes actual. Si ya hay un período anterior, también prepara los meses intermedios según las revisiones vigentes. La preparación se puede repetir sin crear otra ocurrencia para el mismo concepto, mes y cuota.

| Concepto | Regla |
| --- | --- |
| Saldos y reservas | Los saldos contabilizados y las reservas permanecen en las cuentas. Abrir el mes no crea un ingreso, gasto ni ajuste de saldo. El comparativo de Pronóstico reconstruye el saldo inicial desde los movimientos anteriores y distingue los saldos registrados de las previsiones de cierre. |
| Presupuesto por categoría | El nuevo mes se puede preparar copiando un presupuesto en Config. El saldo de una categoría pasa de mes únicamente si se configuró para acumularlo. |
| Salario | Se genera una recepción esperada para la cuenta sugerida y la revisión vigente. La regla nominal por defecto selecciona el último día de lunes a viernes. Para acreditar dinero, en Config → Ingresos se confirma la recepción real o se vincula un ingreso existente. Confirmar registra el movimiento y su cuenta. Vincular reconoce el movimiento sin registrarlo otra vez. |
| Gastos contabilizados | Conservan su fecha e historial en Movimientos. Abrir un nuevo período no modifica ni vuelve a registrar esos movimientos. |
| Gastos y suscripciones impagos | Siguen visibles mientras quede un saldo pendiente. La fila conserva su fecha de vencimiento, mes de origen e identificador. El mes corriente puede mostrar la ocurrencia nueva del mismo concepto junto a la anterior. Solo la anterior lleva la etiqueta Pendiente, además del mes de origen. |
| Deudas y cuotas | El principal permanece en la deuda original. Las cuotas anteriores que sigan impagas aparecen junto a las del mes actual mientras exista saldo de la deuda. El saldo de tarjeta figura una sola vez en Restante por pagar. |

El arrastre en Pagos pendientes del mes es una vista de la obligación original. Cuando se paga, se aplica el importe a esa obligación aunque la fecha real esté en otro mes. Pronóstico conserva la asignación de financiación explícita y señala pendientes de períodos anteriores para revisión. El cierre mensual es una vista reconstruida del historial. El sistema no sella una copia inmutable del cierre y conserva las correcciones auditadas.
