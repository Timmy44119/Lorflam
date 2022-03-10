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
package com.axelor.apps.sale.service.configurator;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.sale.db.Configurator;
import com.axelor.apps.sale.db.ConfiguratorCreator;
import com.axelor.apps.sale.db.ConfiguratorFormula;
import com.axelor.apps.sale.db.ConfiguratorSOLineFormula;
import com.axelor.apps.sale.db.SaleOrder;
import com.axelor.apps.sale.db.SaleOrderLine;
import com.axelor.apps.sale.db.repo.ConfiguratorRepository;
import com.axelor.apps.sale.db.repo.SaleOrderLineRepository;
import com.axelor.apps.sale.db.repo.SaleOrderRepository;
import com.axelor.apps.sale.exception.IExceptionMessage;
import com.axelor.apps.sale.service.saleorder.SaleOrderComputeService;
import com.axelor.apps.sale.service.saleorder.SaleOrderLineService;
import com.axelor.apps.tool.MetaTool;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.db.mapper.Mapper;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaField;
import com.axelor.meta.db.MetaJsonField;
import com.axelor.meta.db.repo.MetaFieldRepository;
import com.axelor.rpc.JsonContext;
import com.axelor.script.GroovyScriptHelper;
import com.axelor.script.ScriptHelper;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import groovy.lang.MissingPropertyException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ConfiguratorServiceImpl implements ConfiguratorService {

  protected AppBaseService appBaseService;
  protected MetaFieldRepository metaFieldRepository;

  @Inject
  public ConfiguratorServiceImpl(
      AppBaseService appBaseService, MetaFieldRepository metaFieldRepository) {
    this.appBaseService = appBaseService;
    this.metaFieldRepository = metaFieldRepository;
  }

  @Override
  public void updateIndicators(
      Configurator configurator, JsonContext jsonAttributes, JsonContext jsonIndicators)
      throws AxelorException {
    if (configurator.getConfiguratorCreator() == null) {
      return;
    }
    List<MetaJsonField> indicators = configurator.getConfiguratorCreator().getIndicators();
    indicators =
        indicators.stream()
            .filter(metaJsonField -> !"one-to-many".equals(metaJsonField.getType()))
            .collect(Collectors.toList());
    for (MetaJsonField indicator : indicators) {
      try {
        Object calculatedValue =
            computeIndicatorValue(configurator, indicator.getName(), jsonAttributes);
        checkType(calculatedValue, indicator);
        jsonIndicators.put(indicator.getName(), calculatedValue);
      } catch (MissingPropertyException e) {
        // if a field is missing, the value needs to be set to null
        continue;
      }
    }
  }

  @Override
  public void checkType(Object calculatedValue, MetaJsonField indicator) throws AxelorException {
    if (calculatedValue == null) {
      return;
    }

    String wantedClassName;
    String wantedType = MetaTool.jsonTypeToType(indicator.getType());

    // do not check one-to-many or many-to-many
    if (wantedType.equals("ManyToMany")
        || wantedType.equals("OneToMany")
        || wantedType.equals("Custom-ManyToMany")
        || wantedType.equals("Custom-OneToMany")) {
      return;
    }
    String calculatedValueClassName =
        Beans.get(ConfiguratorFormulaService.class).getCalculatedClassName(calculatedValue);
    if (wantedType.equals("ManyToOne") || wantedType.equals("Custom-ManyToOne")) {
      // it is a relational field so we get the target model class
      String targetName = indicator.getTargetModel();
      // get only the class without the package
      wantedClassName = targetName.substring(targetName.lastIndexOf('.') + 1);
    } else {
      wantedClassName = wantedType;
    }
    if (!areCompatible(wantedClassName, calculatedValueClassName)) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(IExceptionMessage.CONFIGURATOR_ON_GENERATING_TYPE_ERROR),
          indicator.getName().substring(0, indicator.getName().indexOf('_')),
          wantedClassName,
          calculatedValueClassName);
    }
  }

  /**
   * Here we only generate a product.
   *
   * @param configurator
   * @param jsonAttributes
   * @param jsonIndicators
   */
  @Override
  @Transactional(rollbackOn = {Exception.class})
  public void generate(
      Configurator configurator, JsonContext jsonAttributes, JsonContext jsonIndicators)
      throws AxelorException {
    generateProduct(configurator, jsonAttributes, jsonIndicators);
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public void generateProduct(
      Configurator configurator, JsonContext jsonAttributes, JsonContext jsonIndicators)
      throws AxelorException {

    cleanIndicators(jsonIndicators);
    Mapper mapper = Mapper.of(Product.class);
    Product product = new Product();
    for (String key : jsonIndicators.keySet()) {
      mapper.set(product, key, jsonIndicators.get(key));
    }
    fixRelationalFields(product);
    fetchManyToManyFields(product);
    fillOneToManyFields(configurator, product, jsonAttributes);
    if (product.getProductTypeSelect() == null) {
      product.setProductTypeSelect(ProductRepository.PRODUCT_TYPE_STORABLE);
    }

    if (product.getCode() == null) {
      throw new AxelorException(
          configurator,
          TraceBackRepository.CATEGORY_MISSING_FIELD,
          I18n.get(IExceptionMessage.CONFIGURATOR_PRODUCT_MISSING_CODE));
    }
    if (product.getName() == null) {
      throw new AxelorException(
          configurator,
          TraceBackRepository.CATEGORY_MISSING_FIELD,
          I18n.get(IExceptionMessage.CONFIGURATOR_PRODUCT_MISSING_NAME));
    }

    configurator.setProduct(product);
    product.setConfigurator(configurator);
    Beans.get(ProductRepository.class).save(product);
  }

  @Transactional(rollbackOn = {Exception.class})
  @Override
  public void addLineToSaleOrder(
      Configurator configurator,
      SaleOrder saleOrder,
      JsonContext jsonAttributes,
      JsonContext jsonIndicators)
      throws AxelorException {

    SaleOrderLine saleOrderLine;
    if (configurator.getConfiguratorCreator().getGenerateProduct()) {
      // generate sale order line from product
      saleOrderLine = new SaleOrderLine();
      saleOrderLine.setSaleOrder(saleOrder);
      generate(configurator, jsonAttributes, jsonIndicators);

      saleOrderLine.setProduct(configurator.getProduct());
      this.fillSaleOrderWithProduct(saleOrderLine);
      Beans.get(SaleOrderLineService.class)
          .computeValues(saleOrderLine.getSaleOrder(), saleOrderLine);

      String qtyFormula = configurator.getConfiguratorCreator().getQtyFormula();
      BigDecimal qty = BigDecimal.ONE;
      if (qtyFormula != null && !"".equals(qtyFormula)) {
        Object result = computeFormula(qtyFormula, jsonAttributes);
        if (result != null) {
          qty = new BigDecimal(result.toString());
        }
      }
      saleOrderLine.setQty(qty);
      Beans.get(SaleOrderLineRepository.class).save(saleOrderLine);
    } else {
      generateSaleOrderLine(configurator, jsonAttributes, jsonIndicators, saleOrder);
    }
    Beans.get(SaleOrderComputeService.class).computeSaleOrder(saleOrder);

    Beans.get(SaleOrderRepository.class).save(saleOrder);
  }

  /**
   * Fill fields of sale order line from its product
   *
   * @param saleOrderLine
   */
  protected void fillSaleOrderWithProduct(SaleOrderLine saleOrderLine) throws AxelorException {
    SaleOrderLineService saleOrderLineService = Beans.get(SaleOrderLineService.class);
    if (saleOrderLine.getProduct() != null) {
      saleOrderLineService.computeProductInformation(saleOrderLine, saleOrderLine.getSaleOrder());
    }
  }

  protected void overwriteFieldToUpdate(
      Configurator configurator, SaleOrderLine saleOrderLine, JsonContext attributes)
      throws AxelorException {
    // update a field if its formula has updateFromSelect to update
    // from configurator
    List<ConfiguratorSOLineFormula> formulas =
        configurator.getConfiguratorCreator().getConfiguratorSOLineFormulaList();
    if (formulas != null) {
      Mapper mapper = Mapper.of(SaleOrderLine.class);
      for (ConfiguratorSOLineFormula formula : formulas) {
        // exclude the product field
        if (formula.getUpdateFromSelect() == ConfiguratorRepository.UPDATE_FROM_CONFIGURATOR) {
          // we add "_1" because computeIndicatorValue expect an indicator name.
          Object valueToUpdate =
              computeIndicatorValue(
                  configurator, formula.getMetaField().getName() + "_1", attributes);
          // if many to one, go search value in database.
          if ("ManyToOne".equals(formula.getMetaField().getRelationship())) {
            fixRelationalField(saleOrderLine, (Model) valueToUpdate, formula.getMetaField());
          } else {
            mapper.set(saleOrderLine, formula.getMetaField().getName(), valueToUpdate);
          }
        }
      }
    }
  }

  /**
   * Compute the value of one indicator. Using the corresponding formula and the values in {@link
   * Configurator#attributes}
   *
   * @param configurator
   * @param indicatorName
   * @param jsonAttributes
   * @return
   */
  protected Object computeIndicatorValue(
      Configurator configurator, String indicatorName, JsonContext jsonAttributes) {
    ConfiguratorCreator creator = configurator.getConfiguratorCreator();
    List<? extends ConfiguratorFormula> formulas;
    if (creator.getGenerateProduct()) {
      formulas = creator.getConfiguratorProductFormulaList();
    } else {
      formulas = creator.getConfiguratorSOLineFormulaList();
    }
    String groovyFormula = null;
    for (ConfiguratorFormula formula : formulas) {
      String fieldName = indicatorName;
      fieldName = fieldName.substring(0, fieldName.indexOf('_'));
      MetaField metaField = formula.getMetaField();
      if (metaField.getName().equals(fieldName)) {
        groovyFormula = formula.getFormula();
        break;
      }
    }
    if (groovyFormula == null || jsonAttributes == null) {
      return null;
    }
    return computeFormula(groovyFormula, jsonAttributes);
  }

  @Override
  public Object computeFormula(String groovyFormula, JsonContext values) {
    User currentUser = AuthUtils.getUser();
    Company company = currentUser != null ? currentUser.getActiveCompany() : null;

    values.put("__user__", currentUser);
    values.put("__date__", appBaseService.getTodayDate(company));
    values.put("__datetime__", appBaseService.getTodayDateTime(company));
    ScriptHelper scriptHelper = new GroovyScriptHelper(values);

    return scriptHelper.eval(groovyFormula);
  }

  public boolean areCompatible(String targetClassName, String fromClassName) {
    return targetClassName.equals(fromClassName)
        || (targetClassName.equals("BigDecimal") && fromClassName.equals("Integer"))
        || (targetClassName.equals("BigDecimal") && fromClassName.equals("String"))
        || (targetClassName.equals("String") && fromClassName.equals("GStringImpl"))
        || "ArrayList".equals(fromClassName);
  }

  /**
   * Create a sale order line from the configurator
   *
   * @param configurator
   * @param jsonAttributes
   * @param jsonIndicators
   * @param saleOrder
   * @return
   */
  protected SaleOrderLine generateSaleOrderLine(
      Configurator configurator,
      JsonContext jsonAttributes,
      JsonContext jsonIndicators,
      SaleOrder saleOrder)
      throws AxelorException {
    cleanIndicators(jsonIndicators);
    SaleOrderLine saleOrderLine = Mapper.toBean(SaleOrderLine.class, jsonIndicators);
    saleOrderLine.setSaleOrder(saleOrder);
    fixRelationalFields(saleOrderLine);
    fetchManyToManyFields(saleOrderLine);
    fillOneToManyFields(configurator, saleOrderLine, jsonAttributes);
    this.fillSaleOrderWithProduct(saleOrderLine);
    this.overwriteFieldToUpdate(configurator, saleOrderLine, jsonAttributes);
    if (saleOrderLine.getProductName() == null) {
      throw new AxelorException(
          configurator,
          TraceBackRepository.CATEGORY_MISSING_FIELD,
          I18n.get(IExceptionMessage.CONFIGURATOR_SALE_ORDER_LINE_MISSING_PRODUCT_NAME));
    }
    saleOrderLine = Beans.get(SaleOrderLineRepository.class).save(saleOrderLine);
    Beans.get(SaleOrderLineService.class)
        .computeValues(saleOrderLine.getSaleOrder(), saleOrderLine);
    return saleOrderLine;
  }

  /**
   * Indicator keys have this pattern : {field name}_{id} Transform the keys to have only the {field
   * name}.
   *
   * @param jsonIndicators
   */
  protected void cleanIndicators(JsonContext jsonIndicators) {
    Map<String, Object> newKeyMap = new HashMap<>();
    for (Map.Entry entry : jsonIndicators.entrySet()) {
      String oldKey = entry.getKey().toString();
      newKeyMap.put(oldKey.substring(0, oldKey.indexOf('_')), entry.getValue());
    }
    jsonIndicators.clear();
    jsonIndicators.putAll(newKeyMap);
  }

  protected void fillOneToManyFields(
      Configurator configurator, Model model, JsonContext jsonAttributes) throws AxelorException {
    try {

      ConfiguratorCreator creator = configurator.getConfiguratorCreator();
      List<? extends ConfiguratorFormula> configuratorFormulaList;
      Class[] methodArg = new Class[1];
      if (creator.getGenerateProduct()) {
        configuratorFormulaList = creator.getConfiguratorProductFormulaList();
        methodArg[0] = Product.class;
      } else {
        configuratorFormulaList = creator.getConfiguratorSOLineFormulaList();
        methodArg[0] = SaleOrderLine.class;
      }
      configuratorFormulaList =
          configuratorFormulaList.stream()
              .filter(
                  configuratorFormula ->
                      "OneToMany".equals(configuratorFormula.getMetaField().getRelationship()))
              .collect(Collectors.toList());
      for (ConfiguratorFormula formula : configuratorFormulaList) {
        List<? extends Model> computedValue =
            (List<? extends Model>) computeFormula(formula.getFormula(), jsonAttributes);
        if (computedValue == null) {
          continue;
        }
        Method setMappedByMethod = computeMappedByMethod(formula);
        for (Model listElement : computedValue) {
          setMappedByMethod.invoke(listElement, model);
          JPA.save(listElement);
        }
      }
    } catch (InvocationTargetException | IllegalAccessException | ClassNotFoundException e) {
      throw new AxelorException(e, TraceBackRepository.CATEGORY_INCONSISTENCY);
    }
  }

  /**
   * Find the method used to fill the mapped by many-to-one of a one-to-many relationship. Example:
   * for a one-to-many "purchaseProductMultipleQtyList" with a mapped by many-to-one called
   * "purchaseProduct", this method will return the method "setPurchaseProduct"
   *
   * @param oneToManyFormula a ConfiguratorFormula used to fill a one-to-many
   * @return the found method
   * @throws AxelorException if the mapped by field in meta field is empty.
   */
  protected Method computeMappedByMethod(ConfiguratorFormula oneToManyFormula)
      throws AxelorException, ClassNotFoundException {
    MetaField metaField = oneToManyFormula.getMetaField();
    String mappedBy = metaField.getMappedBy();
    if (mappedBy == null || "".equals(mappedBy)) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.CONFIGURATOR_ONE_TO_MANY_WITHOUT_MAPPED_BY_UNSUPPORTED));
    }
    return Mapper.of(Class.forName(MetaTool.computeFullClassName(metaField))).getSetter(mappedBy);
  }

  /**
   * Fix relational fields of a product or a sale order line generated from a configurator. This
   * method may become useless on a future ADK update.
   *
   * @param model
   */
  protected void fetchManyToManyFields(Model model) throws AxelorException {
    // get all many to many fields
    List<MetaField> manyToManyFields =
        metaFieldRepository
            .all()
            .filter("self.metaModel.name = :name " + "AND self.relationship = 'ManyToMany'")
            .bind("name", model.getClass().getSimpleName())
            .fetch();

    Mapper mapper = Mapper.of(model.getClass());
    for (MetaField manyToManyField : manyToManyFields) {
      Set<? extends Model> manyToManyValue =
          (Set<? extends Model>) mapper.get(model, manyToManyField.getName());
      fetchManyToManyField(model, manyToManyValue, manyToManyField);
    }
  }

  protected void fixRelationalFields(Model model) throws AxelorException {
    // get all many to one fields
    List<MetaField> manyToOneFields =
        metaFieldRepository
            .all()
            .filter("self.metaModel.name = :name " + "AND self.relationship = 'ManyToOne'")
            .bind("name", model.getClass().getSimpleName())
            .fetch();

    Mapper mapper = Mapper.of(model.getClass());
    for (MetaField manyToOneField : manyToOneFields) {
      Model manyToOneValue = (Model) mapper.get(model, manyToOneField.getName());
      fixRelationalField(model, manyToOneValue, manyToOneField);
    }
  }

  protected void fixRelationalField(Model parentModel, Model value, MetaField metaField)
      throws AxelorException {
    if (value != null) {
      Mapper mapper = Mapper.of(parentModel.getClass());
      try {
        String className = MetaTool.computeFullClassName(metaField);
        Model manyToOneDbValue = JPA.find((Class<Model>) Class.forName(className), value.getId());
        mapper.set(parentModel, metaField.getName(), manyToOneDbValue);
      } catch (Exception e) {
        throw new AxelorException(
            Configurator.class, TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, e.getMessage());
      }
    }
  }

  protected void fetchManyToManyField(
      Model parentModel, Set<? extends Model> values, MetaField metaField) throws AxelorException {
    if (values != null) {
      Mapper mapper = Mapper.of(parentModel.getClass());
      try {
        String className = MetaTool.computeFullClassName(metaField);
        Set<Model> dbValues = new HashSet<>();
        for (Model value : values) {
          Model dbValue = JPA.find((Class<Model>) Class.forName(className), value.getId());
          dbValues.add(dbValue);
        }
        mapper.set(parentModel, metaField.getName(), dbValues);
      } catch (Exception e) {
        throw new AxelorException(
            Configurator.class, TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, e.getMessage());
      }
    }
  }
}
