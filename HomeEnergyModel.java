import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvValidationException;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.lang.reflect.Field;
import java.net.IDN;

public class HomeEnergyModel {
    // MODEL PARAMS
    public ArrayList<MonthlyDatum> monthlyData;
    public ArrayList<BuildingComponent> buildingComponents;
    public Map<Integer, Double> solarHeatGainCoefficients;
    public Map<Integer, Map<String, SHGParameter>> solarHeatGainParams;
    public ArrayList<Bill> actualGasBills; 
    public ArrayList<Bill> actualCoolingElectricityBills; 
    
    // MODEL OUTPUT
    public ArrayList<Bill> modelledGasBills; 
    public ArrayList<Bill> modelledCoolingElectricityBills; 
    public ArrayList<MonthlySnapshot> modelledMonthlyGasUsage; 
    public ArrayList<MonthlySnapshot> modelledMonthlyCoolingElectricityUsage;
    
    //CONSTANTS
    private static final String GLAZING = "G"; 
    private static final double W_TO_KW = 0.001;
    private static final double INDOOR_TEMP = 21.1; 
    private static final double BASE_GAS_USAGE = 732.503; //kWh (~ 25 therm)
    private static final double FURNACE_EFF = 0.96; 
    private double K_BASE_VENT = 142; // Changed to instance var. for home improvement comparison 
    private static final double BASE_VENT_TEMP_DIFF = 21.1; 
    private static final double COP = 4.27; 

    // STRUCTS //

    public class MonthlyDatum {
        public int month;
        public int numDays;
        public double avgTemp;
        public int avgNumDaylightHours;
        public double avgBeamFlux;
        public double avgDiffuseFlux;

        @Override
        public String toString() {
            return "Month: " + month + ", NumDays: " + numDays + ", AvgTemp: " + avgTemp +
                    ", AvgNumDaylightHours: " + avgNumDaylightHours +
                    ", AvgBeamFlux: " + avgBeamFlux + ", AvgDiffuseFlux: " + avgDiffuseFlux;
        }
    }

    public class BuildingComponent {
        public String name;
        public String type;
        public String orientation;
        public double area;
        public double transmittance;

        @Override
        public String toString() {
            return "Name: " + name + ", Type: " + type + ", Orientation: " + orientation +
                    ", Area: " + area + ", Transmittance: " + transmittance;
        }
    }

    public class SHGParameter {
        public int theta;
        public double percentageExposure;

        @Override
        public String toString() {
            return "Theta: " + theta + ", PercentageExposure: " + percentageExposure;
        }
    }

    public class Bill {
        int startMonth; 
        int endMonth; 
        double usage; 
        double cost; 
        double rate; 

        @Override
        public String toString() {
            return "Start month: " + startMonth + ", End month: " + endMonth + ", Usage (kWh): " + usage + 
                ", Cost ($USD): " + cost + ", Rate ($USD/kWh): " + rate; 
        }
    }

    public class MonthlySnapshot {
        int month; 
        double heatLoss;
        double heatGain; 
        double usage; 

        @Override
        public String toString() {
            return "Month: " + month + ", Heat loss (kWh): " + heatLoss + ", Heat gain (kWh): " + heatGain +
                ", Usage (kWh): " + usage; 
        }
    }

    public class BillComparison {
        int startMonth; 
        int endMonth; 
        double usage1;
        double usage2; 
        double cost1;
        double cost2; 
        double usagePercentageErrorOrChange;
        double costPercentageErrorOrChange; 
    }

    // INITIALIZATION & FILE MANAGEMENT //

    public HomeEnergyModel(String monthlyDataFile, String buildingComponentFile, String shgcFile, String shgParameterFile, String gasBillsFile, String coolingBillsFile, double ventFactor) throws IOException {
        // Read data from CSV files into model paramater variables
        monthlyData = readMonthlyData(monthlyDataFile);
        buildingComponents = readBuildingComponents(buildingComponentFile);
        solarHeatGainCoefficients = readSHGCs(shgcFile);
        solarHeatGainParams = readSHGParameters(shgParameterFile);
        actualGasBills = readBills(gasBillsFile);
        actualCoolingElectricityBills = readBills(coolingBillsFile);
        if (ventFactor != -1) K_BASE_VENT = ventFactor; 
        // Compute usage
        computeAnnualUsage();
    }

    private ArrayList<MonthlyDatum> readMonthlyData(String fileName) throws IOException {
        ArrayList<MonthlyDatum> list = new ArrayList<>();
        try {
            CSVReader reader = new CSVReaderBuilder(new FileReader(fileName)).build();
            String[] nextLine;
            while ((nextLine = reader.readNext()) != null) {
                MonthlyDatum md = new MonthlyDatum();
                md.month = Integer.parseInt(nextLine[0]);
                md.numDays = Integer.parseInt(nextLine[2]);
                md.avgTemp = Double.parseDouble(nextLine[3]);
                md.avgNumDaylightHours = Integer.parseInt(nextLine[4]);
                md.avgBeamFlux = Double.parseDouble(nextLine[5]);
                md.avgDiffuseFlux = Double.parseDouble(nextLine[6]);
                list.add(md);
            }
            reader.close();
        } catch (IOException | CsvValidationException e) {
            e.printStackTrace();
        }
        return list;
    }

    private ArrayList<BuildingComponent> readBuildingComponents(String fileName) throws IOException {
        ArrayList<BuildingComponent> list = new ArrayList<>();
        try {
            CSVReader reader = new CSVReaderBuilder(new FileReader(fileName)).build();
            String[] nextLine;
            while ((nextLine = reader.readNext()) != null) {
                BuildingComponent bc = new BuildingComponent();
                bc.name = nextLine[0];
                bc.type = nextLine[1];
                bc.orientation = nextLine[2];
                bc.area = Double.parseDouble(nextLine[3]);
                bc.transmittance = Double.parseDouble(nextLine[4]);
                list.add(bc);
            }
            reader.close();
        } catch (IOException | CsvValidationException e) {
            e.printStackTrace();
        }
        return list;
    }

    private Map<Integer, Double> readSHGCs(String fileName) throws IOException {
        Map<Integer, Double> map = new HashMap<>();
        try {
            CSVReader reader = new CSVReaderBuilder(new FileReader(fileName)).build();
            String[] nextLine;
            while ((nextLine = reader.readNext()) != null) {
                map.put(Integer.parseInt(nextLine[0]), Double.parseDouble(nextLine[1]));
            }
            reader.close();   
        } catch (IOException | CsvValidationException e) {
            e.printStackTrace();
        }
        return map;
    }

    private Map<Integer, Map<String, SHGParameter>> readSHGParameters(String fileName) throws IOException {
        Map<Integer, Map<String, SHGParameter>> map = new HashMap<>();
        try {
            CSVReader reader = new CSVReaderBuilder(new FileReader(fileName)).build();
            String[] nextLine;
            while ((nextLine = reader.readNext()) != null) {
                String[] months = nextLine[0].split(",");
                SHGParameter sp = new SHGParameter();
                sp.theta = Integer.parseInt(nextLine[2]);
                sp.percentageExposure = Double.parseDouble(nextLine[3]) / 100;
                for (String month : months) {
                    int m = Integer.parseInt(month);
                    Map<String, SHGParameter> subMap = map.getOrDefault(m, new HashMap<>());
                    subMap.put(nextLine[1], sp);
                    map.put(m, subMap);
                }
            }
            reader.close();
        } catch (IOException | CsvValidationException e) {
            e.printStackTrace();
        }
        return map;
    }

    private ArrayList<Bill> readBills(String fileName) throws IOException {
        ArrayList<Bill> list = new ArrayList<>();
        try {
            CSVReader reader = new CSVReaderBuilder(new FileReader(fileName)).build();
            String[] nextLine;
            while ((nextLine = reader.readNext()) != null) {
                Bill bill = new Bill();
                bill.startMonth = Integer.parseInt(nextLine[0]);
                bill.endMonth = Integer.parseInt(nextLine[1]);
                bill.usage = Double.parseDouble(nextLine[2]);
                bill.cost = Double.parseDouble(nextLine[3]);
                bill.rate = Double.parseDouble(nextLine[4]);
                list.add(bill);
            }
            reader.close();
        } catch (IOException | CsvValidationException e) {
            e.printStackTrace();
        }
        return list;
    }

    private void writeToCSV(String fileName, ArrayList<String> content) {
        try (CSVWriter writer = new CSVWriter(new FileWriter(fileName))) {
            for (String line : content) {
                String[] entries = line.split(",");
                writer.writeNext(entries);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void printModelParameters() {
        
        System.out.println("\n***MONTHLY DATA*** \n");

        for (MonthlyDatum datum : monthlyData) {
            System.out.println(datum.toString());
        }

        System.out.println("\n***BUILDING COMPONENTS*** \n");

        for (BuildingComponent bComponent: buildingComponents) {
            System.out.println(bComponent.toString());
        }

        System.out.println("\n***SHGCs***\n");

        for (Map.Entry<Integer, Double> entry : solarHeatGainCoefficients.entrySet()) {
            System.out.println("Theta:" + entry.getKey() + " SHGC:" + entry.getValue());
        }
        
        System.out.println("\n***SHG PARAMETERS***\n");
        
        for (Map.Entry<Integer, Map<String, SHGParameter>> entry : solarHeatGainParams.entrySet()) {
            System.out.println("Month:" + entry.getKey() + "\n");            
            for (Map.Entry<String, SHGParameter> entry2 : entry.getValue().entrySet()) {
                System.out.println("Orientation:" + entry2.getKey() + "-> Params:" + entry2.getValue());
            }
            System.out.println();
        }

        System.out.println("\n***GAS BILLS***\n");
        
        for(Bill bill : actualGasBills) {
            System.out.println(bill.toString());
        }

        System.out.println("\n***COOLING ELECTRICITY BILLS***\n");
        
        for(Bill bill : actualCoolingElectricityBills) {
            System.out.println(bill.toString());
        }

    }

    // COMPUTATION //

    // Returns adjusted ventilation factor depending on the temp. difference for specified month
    private double getAdjustedVentilationFactor(int month) {
        MonthlyDatum monthlyDatum = monthlyData.get(month - 1);
        return K_BASE_VENT * (Math.abs(INDOOR_TEMP - monthlyDatum.avgTemp) / BASE_VENT_TEMP_DIFF); 
    }

    // Returns temp. dependant heat transfer (i.e., via conduction and ventilation) for specified month (kWh/month)
    // If return value is positive: heat gain, else: heat loss
    private double computeMonthlyTempDepHeatTransfer(int month) {
        // k is the sum of all UA values and the adjusted ventilation factor 
        double k = 0;
        // monthlyData[0] contains data for month 1!
        MonthlyDatum monthlyDatum = monthlyData.get(month - 1); 

        // Sum all UA values
        for (BuildingComponent bComponent : buildingComponents) {
            k += bComponent.transmittance * bComponent.area; 
        }

        // Add adjusted ventilation factor to total factor
        k += getAdjustedVentilationFactor(month);

        // Q_dot = k(T_i - T_o) * 1/1000 kW/W
        Double heatTransferRate = k * (monthlyDatum.avgTemp - INDOOR_TEMP) * W_TO_KW; // kW
        // E_monthly = Q_dot * hours per day * days per month
        return heatTransferRate * 24 * monthlyDatum.numDays; // kWh/month
    }
    
    // Returns solar heat gain for specified month (kWh/month)
    private double computeMonthlySHG(int month) {
        double solarHeatGain = 0; 
        MonthlyDatum monthlyDatum = monthlyData.get(month - 1);

        // Get glazing building components
        for (BuildingComponent bComponent : buildingComponents) {
            if (!bComponent.type.equals(GLAZING)) continue; 
            
            // Get avg. angle of incidence and exposure (percentage of daylight hours) to beam radiation for glazing component
            SHGParameter angleAndExposure = solarHeatGainParams.get(month).get(bComponent.orientation); 
            
            // Compute solar heat gain fluxes in W/m^2 //

            // Q''_B = E''_B,N * cos(theta) * SHGC_theta
            double beamHeatGainFlux = monthlyDatum.avgBeamFlux * Math.cos(Math.toRadians(angleAndExposure.theta)) * solarHeatGainCoefficients.get(angleAndExposure.theta); 
            //Q''_DR = E''_DR * SHGC_DR
            double diffuseHeatGainFlux = monthlyDatum.avgDiffuseFlux * solarHeatGainCoefficients.get(-1); // Diffuse SHGC mapped to theta = -1
            // E_gain_glazing_monthly = [(Q''_B * c_exposure) + Q''_DR] * 1/1000 kW/W * A * n_daily_daylight_hours * n_days_in_month
            solarHeatGain += ((beamHeatGainFlux * angleAndExposure.percentageExposure) + diffuseHeatGainFlux) * W_TO_KW * bComponent.area * monthlyDatum.avgNumDaylightHours * monthlyDatum.numDays; 
        }
        return solarHeatGain; 
    }

    // Updates modelled usage and bill lists
    private void computeAnnualUsage() {
        // Create MonthlySnapshot lists to hold computed usage for each month
        ArrayList<MonthlySnapshot> monthlyGasUsage = new ArrayList<>(); 
        ArrayList<MonthlySnapshot> monthlyCoolingElectricityUsage = new ArrayList<>(); 
        
        // For each month...
        for (int i = 0; i < 12; i++) {
            
            double tempDepHT, solarHeatGain, heatLoss, heatGain, gasUsage, coolingElectricityUsage;
            tempDepHT = computeMonthlyTempDepHeatTransfer(i + 1);
            solarHeatGain = computeMonthlySHG(i + 1);
            heatLoss = 0; 
            heatGain = solarHeatGain;
            gasUsage = BASE_GAS_USAGE;
            coolingElectricityUsage = 0; 

            if (tempDepHT < 0) heatLoss = tempDepHT * -1; // Heat loss occurs
            else heatGain += tempDepHT; // Only heat gain occurs
            if (heatLoss > heatGain) gasUsage += (heatLoss - heatGain) / FURNACE_EFF; // Furnace is only ON if net heat loss > 0
            else coolingElectricityUsage = (heatGain - heatLoss) / COP; // AC is only ON if net heat gain > 0 

            MonthlySnapshot gasSnapshot = new MonthlySnapshot(); 
            MonthlySnapshot coolingElectricitySnapshot = new MonthlySnapshot();

            gasSnapshot.month = i + 1;
            gasSnapshot.heatLoss = heatLoss;
            gasSnapshot.heatGain = heatGain;
            gasSnapshot.usage = gasUsage; 
            
            coolingElectricitySnapshot.month = i + 1;
            coolingElectricitySnapshot.heatLoss = heatLoss;
            coolingElectricitySnapshot.heatGain = heatGain;
            coolingElectricitySnapshot.usage = coolingElectricityUsage; 

            monthlyGasUsage.add(gasSnapshot);
            monthlyCoolingElectricityUsage.add(coolingElectricitySnapshot); 
        }

        modelledMonthlyGasUsage = monthlyGasUsage;
        modelledMonthlyCoolingElectricityUsage = monthlyCoolingElectricityUsage; 
        modelledGasBills = generateModelledBills(actualGasBills, monthlyGasUsage);
        modelledCoolingElectricityBills = generateModelledBills(actualCoolingElectricityBills, monthlyCoolingElectricityUsage); 
    }

    // Returns list of modelled bills by roughly calendarizing usage data and using actual rates
    private ArrayList<Bill> generateModelledBills(ArrayList<Bill> actualBills, ArrayList<MonthlySnapshot> modelledUsage) {
        ArrayList<Bill> modelledBills = new ArrayList<>();

        for (Bill bill : actualBills) {
            MonthlySnapshot startMonthSnapshot = modelledUsage.get(bill.startMonth - 1);
            MonthlySnapshot endMonthSnapshot = modelledUsage.get(bill.endMonth - 1);

            Double startMonthUsage = startMonthSnapshot.usage / 2;
            Double endMonthUsage = endMonthSnapshot.usage / 2; 

            Bill modelledBill = new Bill();
            modelledBill.startMonth = bill.startMonth;
            modelledBill.endMonth = bill.endMonth;
            modelledBill.usage = startMonthUsage + endMonthUsage;
            modelledBill.cost = bill.rate * modelledBill.usage; 
            modelledBill.rate = bill.rate; 

            modelledBills.add(modelledBill);
        }

        return modelledBills;
    }
    
    // OUTPUT & COMPARISON TABULATION //

    // Writes modlled usage data for either gas or cooling electricity to CSV file
    public void writeModelledUsageToCSV(String flag, String fileName) {
        String usageType;
        ArrayList<MonthlySnapshot> modelledUsage; 
    
        // String flag = "G" for gas usage, and "E" for cooling electricty usage 
        if (flag.equals("G")) {
            usageType = "Gas Usage (kWh)";
            modelledUsage = modelledMonthlyGasUsage;
        } else {
            usageType = "Cooling Electricity Usage (kWh)";
            modelledUsage = modelledMonthlyCoolingElectricityUsage;
        }
        String header = "Month,Heat Loss (kWh),Heat Gain (kWh)," + usageType; 

        writeToCSV(fileName, objListToCSVStringList(header, null, modelledUsage));
    }

    // Writes proportions of heat transfer caused by conduction, ventilation and solar heat gain each month to CSV file
    public void writeHeatTransferBreakdownToCSV(String fileName) {
        String heatLossHeader = "Month, Conduction (%), Ventilation (%), Heat Loss (kWh)";
        String heatGainHeader = "Month, Conduction (%), Ventilation (%), Solar Heat Gain (%), Heat Gain (kWh)";

        ArrayList<String> heatLossBreakdown = new ArrayList<>(); 
        ArrayList<String> heatGainBreakdown = new ArrayList<>(); 

        heatLossBreakdown.add(heatLossHeader);
        heatGainBreakdown.add(heatGainHeader);        

        double kCond = 0; 
        for (BuildingComponent bComponent : buildingComponents) {
            kCond += bComponent.transmittance * bComponent.area; 
        }

        for (int i = 0; i < 12; i++) {
            MonthlyDatum monthlyDatum = monthlyData.get(i); 
            double kVent = getAdjustedVentilationFactor(i + 1);
            
            double condVentCommonFactor = (monthlyDatum.avgTemp - INDOOR_TEMP) * W_TO_KW * 24 * monthlyDatum.numDays; 
            double conduction = Math.abs(kCond * condVentCommonFactor);
            double ventilation = Math.abs(kVent * condVentCommonFactor);
            double solarHeatGain = computeMonthlySHG(i + 1);
            
            double heatTransfer = computeMonthlyTempDepHeatTransfer(i + 1); // cond + vent

            String line;
            if (heatTransfer < 0) {
                double heatLoss = conduction + ventilation; 
                line = String.valueOf(i + 1) + ", " + String.valueOf((conduction / heatLoss) * 100) + ", " + String.valueOf((ventilation / heatLoss) * 100) + ", " + String.valueOf(heatLoss);  
                heatLossBreakdown.add(line);
            } else {
                double heatGain = conduction + ventilation + solarHeatGain; 
                line = String.valueOf(i + 1) + ", " + String.valueOf((conduction / heatGain) * 100) + ", " + String.valueOf((ventilation / heatGain) * 100) + ", " + 
                   String.valueOf((solarHeatGain / heatGain) * 100) + "," + String.valueOf(heatGain);
                heatGainBreakdown.add(line);
            }
        }

        heatLossBreakdown.add("");
        heatLossBreakdown.addAll(heatGainBreakdown);
        writeToCSV(fileName, heatLossBreakdown);
    }

    // Compares actual and modelled usage and cost and writes results to CSV file 
    public void writeModelAccuracyToCSV(String fileName) {
        String gasHeader, coolingElectricityHeader, gasTotal, coolingElectricityTotal;
        gasHeader = "Billing Start Month, Billing End Month, Actual Gas Usage (kWh), Modelled Gas Usage (kWh)," +
            "Actual Cost ($USD), Modelled Cost ($USD), Usage Error (%), Cost Error (%)";
        coolingElectricityHeader = "Billing Start Month, Billing End Month, Actual Cooling Electricity Usage (kWh), Modelled Cooling Electricity Usage (kWh)," +
            "Actual Cost ($USD), Modelled Cost ($USD), Usage Error (%), Cost Error (%)";

        ArrayList<BillComparison> gasComparisonList = compareBills(actualGasBills, modelledGasBills);
        ArrayList<BillComparison> coolingElectricityComparisonList = compareBills(actualCoolingElectricityBills, modelledCoolingElectricityBills);
        int listSize = gasComparisonList.size(); 

        // Totals are stored in the last element of comparison lists
        gasTotal = "Total " + objListToCSVStringList(null, null, new ArrayList<BillComparison>(gasComparisonList.subList(listSize - 1, listSize))).get(0);
        coolingElectricityTotal = "Total " + objListToCSVStringList(null, null, new ArrayList<BillComparison>(coolingElectricityComparisonList.subList(listSize - 1, listSize))).get(0);

        gasComparisonList.remove(listSize - 1);
        coolingElectricityComparisonList.remove(listSize - 1);

        ArrayList<String> content1, content2; 
        content1 = objListToCSVStringList(gasHeader, gasTotal, gasComparisonList);
        content1.add(""); 
        content2 = objListToCSVStringList(coolingElectricityHeader, coolingElectricityTotal, coolingElectricityComparisonList);
        content1.addAll(content2);

        writeToCSV(fileName, content1);
    }

    // Compares this model (base) with new model and writes results to a CSV file along with the payback period
    public void writeModelComparisonToCSV(String fileName, double additionalCost, HomeEnergyModel improvedHome) {
        
        String gasHeader, coolingElectricityHeader, gasTotal, coolingElectricityTotal;
        gasHeader = "Billing Start Month, Billing End Month, Base Gas Usage (kWh), New Gas Usage (kWh)," +
            "Base Cost ($USD), New Cost ($USD), Usage Reduction (%), Cost Reduction (%)";
        coolingElectricityHeader = "Billing Start Month, Billing End Month, Base Cooling Electricity Usage (kWh), New Cooling Electricity Usage (kWh)," +
            "Base Cost ($USD), New Cost ($USD), Usage Reduction (%), Cost Reduction (%)";

        ArrayList<BillComparison> gasComparisonList = compareBills(modelledGasBills, improvedHome.modelledGasBills);
        ArrayList<BillComparison> coolingElectricityComparisonList = compareBills(modelledCoolingElectricityBills, improvedHome.modelledCoolingElectricityBills);
        int listSize = gasComparisonList.size();

        // Calculate payback period

        BillComparison gasTotalComparison, coolingElectricityTotalComparison; 
        gasTotalComparison = gasComparisonList.get(listSize - 1);
        coolingElectricityTotalComparison = coolingElectricityComparisonList.get(listSize - 1);
        gasComparisonList.remove(listSize - 1);
        coolingElectricityComparisonList.remove(listSize - 1);

        Double totalBaseCost, totalImprovedCost, yearlySavings, payBackYears; 
        totalBaseCost = gasTotalComparison.cost1 + coolingElectricityTotalComparison.cost1;
        totalImprovedCost = gasTotalComparison.cost2 + coolingElectricityTotalComparison.cost2;
        yearlySavings = totalBaseCost - totalImprovedCost;
        payBackYears = additionalCost / yearlySavings; 


        String totalComparisonMessage;
        if (yearlySavings > 0) {
            totalComparisonMessage = "Payback period for $" + additionalCost + " investment with yearly savings of $" + yearlySavings + " is " + payBackYears + " years.";
        } else {
            totalComparisonMessage = "Payback is not possible since supposed improved home is as or more costly!";
        }

        // Construct string list and generate CSV

        ArrayList<String> content1, content2, footers; 
        ArrayList<BillComparison> dummyComparisonList = new ArrayList<>();

        dummyComparisonList.add(gasTotalComparison);
        dummyComparisonList.add(coolingElectricityTotalComparison);

        footers = objListToCSVStringList(null, null, dummyComparisonList);
        gasTotal = "Total" + footers.get(0);
        coolingElectricityTotal = "Total" + footers.get(1);

        content1 = objListToCSVStringList(gasHeader, gasTotal, gasComparisonList);
        content2 = objListToCSVStringList(coolingElectricityHeader, coolingElectricityTotal, coolingElectricityComparisonList);
        content1.add("");
        content1.addAll(content2);
        content1.add("");
        content1.add(totalComparisonMessage);

        writeToCSV(fileName, content1);
    }
    
    // Returns a list of bill comparisons for the two bill lists specified
    private ArrayList<BillComparison> compareBills(ArrayList<Bill> bills1, ArrayList<Bill> bills2) {
        if (bills1.size() != bills2.size()) {
            System.out.println("Error: bills are not comparable (not the same size)!");
            return null; 
        }

        double totalUsage1, totalUsage2, totalCost1, totalCost2; 
        ArrayList<BillComparison> comparisonList = new ArrayList<>();

        totalUsage1 = 0; 
        totalUsage2 = 0;
        totalCost1 = 0;
        totalCost2 = 0;

        for (int i = 0; i < bills1.size(); i++) {
            BillComparison comparison = new BillComparison();
            
            comparison.startMonth = bills1.get(i).startMonth; 
            comparison.endMonth = bills1.get(i).endMonth; 
            
            comparison.usage1 = bills1.get(i).usage;
            comparison.usage2 = bills2.get(i).usage;
            comparison.cost1 = bills1.get(i).cost;
            comparison.cost2 = bills2.get(i).cost;
            
            // Need to check both usages are non-zero so we don't divide by zero

            if (comparison.usage1 != 0 && comparison.usage2 != 0) {
                comparison.usagePercentageErrorOrChange = ( (comparison.usage1 - comparison.usage2) / comparison.usage1 ) * 100; 
                comparison.costPercentageErrorOrChange = ( (comparison.cost1 - comparison.cost2) / comparison.cost1 ) * 100; 
            }
            
            totalUsage1 += comparison.usage1;
            totalUsage2 += comparison.usage2;
            totalCost1 += comparison.cost1;
            totalCost2 += comparison.cost2; 

            comparisonList.add(comparison); 
        }

        // Add total comparison to end of list (start and end months are null)
        BillComparison totalComparison = new BillComparison();
        totalComparison.usage1 = totalUsage1;
        totalComparison.usage2 = totalUsage2;
        totalComparison.cost1 = totalCost1;
        totalComparison.cost2 = totalCost2; 
        totalComparison.usagePercentageErrorOrChange = ( (totalUsage1 - totalUsage2) / totalUsage1 ) * 100; 
        totalComparison.costPercentageErrorOrChange = ( (totalCost1 - totalCost2) / totalCost1 ) * 100; 

        comparisonList.add(totalComparison);
        return comparisonList;  
    }

    // Converts list of objects to list of strings formatted to be written to a CSV file
    private static ArrayList<String> objListToCSVStringList(String header, String footer, ArrayList<?> objs) {
        // Check if the list is empty
        if (objs.isEmpty()) {
            System.out.println("Error: list objs is empty!");
        }
        
        // Get fields (instance variables) and assume all are public
        Field[] fields = objs.get(0).getClass().getDeclaredFields(); 
        
        // List of strings containing comma-seperated values of object fields
        ArrayList<String> content = new ArrayList<>(); 
        if (header != null) content.add(header);  

        for (Object obj : objs) {
            StringBuilder line = new StringBuilder();

            for (Field field : fields) {
                // Ignore the 'this$0' field (synthetic field for nested classes)
                if (field.getName().equals("this$0")) {
                    continue;
                }
                try {
                    Object value = field.get(obj); 
                    if (value != null) line.append(value).append(","); 
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Remove the trailing comma
            if (line.length() > 0) {
                line.setLength(line.length() - 1);
            }

            content.add(line.toString());
        }

        if (footer != null) content.add(footer);
        return content; 
    }

    public static void main(String[] args) {

        HomeEnergyModel model = null; // Model of house HVAC usage in existing state
        HomeEnergyModel improvedVent = null;
        HomeEnergyModel improvedRoof = null;
        HomeEnergyModel improvedWindows = null; 
        
        try {
            model = new HomeEnergyModel("Monthly Data.csv", "Building Components.csv", "SHGCs.csv", "SHG Parameters.csv", "Gas Bills.csv", "Cooling Electricity Bills.csv", -1);
            improvedVent = new HomeEnergyModel("Monthly Data.csv", "Building Components.csv", "SHGCs.csv", "SHG Parameters.csv", "Gas Bills.csv", "Cooling Electricity Bills.csv", 113.6);
            improvedRoof = new HomeEnergyModel("Monthly Data.csv", "Building Components \u2014 Improved Roof Insulation.csv", "SHGCs.csv", "SHG Parameters.csv", "Gas Bills.csv", 
                "Cooling Electricity Bills.csv", -1);
            improvedWindows = new HomeEnergyModel("Monthly Data.csv", "Building Components \u2014 Improved Windows.csv", "SHGCs \u2014 Improved Windows.csv", "SHG Parameters.csv", "Gas Bills.csv", 
                "Cooling Electricity Bills.csv", -1);
        } catch (Exception e) {
            e.printStackTrace();
            return; 
        } 

        // WRITE MODEL OUTPUT & COMPARISON TO CSV FILES
        
        // Modelled monthly usage

        model.writeModelledUsageToCSV("G", "OUT Modelled Gas Usage.csv");
        model.writeModelledUsageToCSV("E", "OUT Modelled Cooling Electricity Usage.csv");
        
        // Heat transfer component breakdown

        model.writeHeatTransferBreakdownToCSV("OUT Heat Transfer Breakdown.csv");

        // Model accuracy
        
        model.writeModelAccuracyToCSV("OUT Model Accuracy.csv");

        // Compare base model with models of home with different improvements
        
        model.writeModelComparisonToCSV("OUT Improved Ventilation Comparison.csv", 1000, improvedVent);
        model.writeModelComparisonToCSV("OUT Improved Roof Insulation Comparison.csv", 5126.8, improvedRoof);
        model.writeModelComparisonToCSV("OUT Improved Windows Comparison.csv", 45000, improvedWindows);

    }
}