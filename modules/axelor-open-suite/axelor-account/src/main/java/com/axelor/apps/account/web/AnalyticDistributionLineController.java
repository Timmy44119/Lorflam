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
package com.axelor.apps.account.web;

import com.axelor.apps.account.db.AnalyticDistributionTemplate;
import com.axelor.apps.account.db.AnalyticMoveLine;
import com.axelor.apps.account.service.AnalyticMoveLineService;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

@Singleton
public class AnalyticDistributionLineController {

  public void computeAmount(ActionRequest request, ActionResponse response) {
    AnalyticMoveLine analyticMoveLine = request.getContext().asType(AnalyticMoveLine.class);
    response.setValue(
        "amount", Beans.get(AnalyticMoveLineService.class).computeAmount(analyticMoveLine));
  }

  public void validateLines(ActionRequest request, ActionResponse response) {
    AnalyticDistributionTemplate analyticDistributionTemplate =
        request.getContext().asType(AnalyticDistributionTemplate.class);
    if (!Beans.get(AnalyticMoveLineService.class)
        .validateLines(analyticDistributionTemplate.getAnalyticDistributionLineList())) {
      response.setError(
          I18n.get("The distribution is wrong, some axes percentage values are higher than 100%"));
    }
  }
}
