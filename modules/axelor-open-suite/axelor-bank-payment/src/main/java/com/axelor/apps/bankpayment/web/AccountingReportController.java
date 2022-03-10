/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2022 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.bankpayment.web;

import com.axelor.apps.account.db.AccountingReport;
import com.axelor.apps.bankpayment.service.AccountingReportBankPaymentService;
import com.axelor.exception.service.TraceBackService;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

@Singleton
public class AccountingReportController {

  public void setBankDetailsDomain(ActionRequest request, ActionResponse response) {
    try {
      AccountingReport accountingReport = request.getContext().asType(AccountingReport.class);
      String domain =
          Beans.get(AccountingReportBankPaymentService.class)
              .createDomainForBankDetails(accountingReport);
      // if nothing was found for the domain, we set it at a default value.
      if (domain.equals("")) {
        response.setAttr("bankDetailsSet", "domain", "self.id IN (0)");
      } else {
        response.setAttr("bankDetailsSet", "domain", domain);
      }
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}
