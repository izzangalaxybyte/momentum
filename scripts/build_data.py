#!/usr/bin/env python3
"""Source of truth for the app's bundled data. Writes:

  data/foods_pk.json        desi food database (values per serving unit; averages, home cooking varies)
  data/exercises.json       exercise catalogue: ladders, cues, mistakes, muscles, demo images
  data/exercises/<id>/0.jpg, 1.jpg   demo frames (only with --media)

Demo images come from free-exercise-db (github.com/yuhonas/free-exercise-db), released under the
Unlicense (public domain). Run:  python3 scripts/build_data.py [--media]
"""

import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
FEDB_RAW = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises"

# id, English name, Roman Urdu name, aliases, unit, grams per unit, kcal, protein g, carbs g, fat g, category
FOODS = [
    # breads
    ("roti", "Roti / chapati", "Roti", ["chapati", "phulka", "chapatti"], "piece", 40, 120, 4, 24, 1, "bread"),
    ("roti-ghee", "Roti with ghee", "Ghee wali roti", ["chupri roti"], "piece", 45, 165, 4, 24, 6, "bread"),
    ("tandoori-roti", "Tandoori roti", "Tandoori roti", ["tandoor roti"], "piece", 70, 190, 6, 38, 1, "bread"),
    ("paratha", "Paratha (plain)", "Paratha", ["parantha", "parotha"], "piece", 80, 300, 6, 40, 13, "bread"),
    ("aloo-paratha", "Aloo paratha", "Aloo paratha", ["potato paratha"], "piece", 120, 350, 7, 48, 15, "bread"),
    ("anda-paratha", "Anda paratha (paratha + fried egg)", "Anda paratha", ["egg paratha"], "plate", 140, 420, 13, 41, 23, "breakfast"),
    ("naan", "Naan (plain)", "Naan", ["nan"], "piece", 100, 290, 9, 50, 6, "bread"),
    ("roghni-naan", "Roghni naan", "Roghni naan", ["roghini naan"], "piece", 110, 350, 9, 52, 11, "bread"),
    ("garlic-naan", "Garlic naan", "Garlic naan", [], "piece", 105, 320, 9, 51, 9, "bread"),
    ("kulcha", "Kulcha", "Kulcha", [], "piece", 95, 280, 8, 48, 6, "bread"),
    ("puri", "Puri", "Puri", ["poori"], "piece", 30, 120, 2, 13, 7, "bread"),
    ("sheermal", "Sheermal", "Sheermal", [], "piece", 100, 330, 8, 50, 11, "bread"),
    ("bakarkhani", "Bakarkhani", "Bakarkhani", [], "piece", 60, 260, 5, 30, 13, "bread"),
    ("makai-roti", "Makki ki roti", "Makai roti", ["makki roti", "corn roti"], "piece", 60, 180, 4, 33, 3, "bread"),
    ("missi-roti", "Missi roti", "Missi roti", ["besan roti"], "piece", 60, 170, 6, 27, 4, "bread"),
    ("bread-white", "Bread slice (white)", "Double roti", ["bread", "sandwich bread"], "slice", 28, 70, 2.5, 13, 1, "bread"),
    ("bread-brown", "Bread slice (brown)", "Brown bread", ["whole wheat bread"], "slice", 28, 70, 3, 12, 1, "bread"),
    ("bun", "Bun / burger bun", "Bun", [], "piece", 55, 150, 5, 27, 2.5, "bread"),
    ("rusk", "Rusk", "Rusk", ["papay", "cake rusk"], "piece", 12, 45, 1, 8, 1, "snack"),
    # rice
    ("rice-plain", "Plain boiled rice", "Saada chawal", ["chawal", "white rice", "boiled rice"], "cup", 160, 205, 4, 45, 0.5, "rice"),
    ("chicken-biryani", "Chicken biryani", "Chicken biryani", ["biryani"], "plate", 300, 500, 25, 60, 18, "rice"),
    ("beef-biryani", "Beef biryani", "Beef biryani", [], "plate", 300, 550, 25, 60, 22, "rice"),
    ("mutton-biryani", "Mutton biryani", "Mutton biryani", [], "plate", 300, 580, 26, 60, 25, "rice"),
    ("sindhi-biryani", "Sindhi biryani", "Sindhi biryani", [], "plate", 300, 560, 25, 62, 23, "rice"),
    ("chicken-pulao", "Chicken pulao", "Chicken pulao", ["yakhni pulao", "pulav"], "plate", 300, 450, 22, 58, 14, "rice"),
    ("beef-pulao", "Beef pulao", "Beef pulao", [], "plate", 300, 500, 24, 58, 18, "rice"),
    ("kabuli-pulao", "Kabuli pulao", "Kabuli pulao", ["afghani pulao"], "plate", 300, 550, 22, 65, 22, "rice"),
    ("matar-pulao", "Matar pulao", "Matar pulao", ["peas pulao"], "plate", 250, 380, 8, 62, 11, "rice"),
    ("daal-chawal", "Daal chawal", "Daal chawal", ["dal chawal"], "plate", 350, 420, 15, 70, 9, "rice"),
    ("khichdi", "Khichdi", "Khichri", ["khichri"], "bowl", 250, 300, 10, 50, 7, "rice"),
    ("fried-rice", "Chicken fried rice", "Fried rice", ["egg fried rice"], "plate", 300, 480, 12, 70, 16, "rice"),
    ("zarda", "Zarda (sweet rice)", "Zarda", ["meethe chawal"], "cup", 150, 350, 4, 65, 9, "sweet"),
    # meat curries
    ("chicken-karahi", "Chicken karahi", "Chicken karahi", ["karahi", "kadai chicken"], "bowl", 200, 380, 30, 8, 26, "curry"),
    ("mutton-karahi", "Mutton karahi", "Mutton karahi", ["gosht karahi"], "bowl", 200, 450, 30, 8, 33, "curry"),
    ("chicken-qorma", "Chicken qorma", "Chicken qorma", ["korma"], "bowl", 200, 400, 28, 10, 28, "curry"),
    ("beef-qorma", "Beef qorma", "Beef qorma", ["mutton korma"], "bowl", 200, 430, 28, 10, 31, "curry"),
    ("chicken-salan", "Chicken curry / salan", "Chicken salan", ["chicken curry", "murgh salan"], "bowl", 200, 300, 25, 8, 19, "curry"),
    ("aloo-gosht", "Aloo gosht", "Aloo gosht", ["potato meat curry"], "bowl", 200, 320, 20, 18, 19, "curry"),
    ("nihari", "Nihari", "Nihari", ["nahari"], "bowl", 250, 480, 30, 10, 35, "curry"),
    ("haleem", "Haleem", "Haleem", ["daleem"], "bowl", 250, 400, 25, 35, 17, "curry"),
    ("paye", "Paye", "Paye", ["siri paye", "trotters"], "bowl", 250, 450, 25, 5, 37, "curry"),
    ("qeema", "Qeema", "Qeema", ["keema", "mince"], "bowl", 200, 400, 28, 6, 30, "curry"),
    ("aloo-qeema", "Aloo qeema", "Aloo qeema", ["aloo keema"], "bowl", 200, 380, 22, 16, 25, "curry"),
    ("qeema-matar", "Qeema matar", "Qeema matar", ["keema matar"], "bowl", 200, 380, 24, 12, 26, "curry"),
    ("chicken-jalfrezi", "Chicken jalfrezi", "Chicken jalfrezi", [], "bowl", 200, 300, 26, 10, 17, "curry"),
    ("butter-chicken", "Butter chicken", "Butter chicken", ["murgh makhani"], "bowl", 200, 450, 28, 12, 32, "curry"),
    ("chicken-tikka-masala", "Chicken tikka masala", "Chicken tikka masala", [], "bowl", 200, 400, 28, 12, 27, "curry"),
    ("chicken-handi", "Chicken handi", "Chicken handi", ["white handi"], "bowl", 200, 420, 28, 8, 31, "curry"),
    ("kofta-curry", "Kofta curry", "Kofta", ["kofte"], "bowl", 200, 420, 22, 12, 31, "curry"),
    ("palak-gosht", "Palak gosht", "Palak gosht", ["saag gosht"], "bowl", 200, 330, 22, 8, 23, "curry"),
    ("karela-gosht", "Karela gosht", "Karela gosht", [], "bowl", 200, 330, 20, 10, 23, "curry"),
    ("bhindi-gosht", "Bhindi gosht", "Bhindi gosht", [], "bowl", 200, 330, 20, 10, 23, "curry"),
    ("daal-gosht", "Daal gosht", "Daal gosht", [], "bowl", 200, 350, 22, 20, 20, "curry"),
    ("egg-curry", "Egg curry (2 eggs)", "Anda curry", ["anday ka salan"], "bowl", 200, 300, 14, 8, 24, "curry"),
    ("fish-curry", "Fish curry", "Machli ka salan", ["fish salan"], "bowl", 200, 280, 24, 6, 18, "curry"),
    ("palak-paneer", "Palak paneer", "Palak paneer", ["saag paneer"], "bowl", 200, 320, 14, 10, 25, "curry"),
    # BBQ / fried
    ("seekh-kebab", "Seekh kebab", "Seekh kabab", ["seekh kabab"], "piece", 50, 130, 10, 2, 9, "bbq"),
    ("shami-kebab", "Shami kebab", "Shami kabab", ["shami kabab"], "piece", 50, 120, 8, 6, 7, "bbq"),
    ("chapli-kebab", "Chapli kebab", "Chapli kabab", ["chapli kabab"], "piece", 100, 300, 18, 6, 23, "bbq"),
    ("chicken-tikka", "Chicken tikka (leg/breast piece)", "Chicken tikka", ["tikka"], "piece", 150, 280, 35, 4, 13, "bbq"),
    ("chicken-boti", "Chicken boti (6 pieces)", "Chicken boti", ["boti"], "serving", 150, 260, 34, 4, 12, "bbq"),
    ("malai-boti", "Malai boti (6 pieces)", "Malai boti", [], "serving", 150, 320, 32, 4, 20, "bbq"),
    ("tandoori-chicken", "Tandoori chicken (quarter)", "Tandoori chicken", [], "piece", 200, 300, 38, 4, 14, "bbq"),
    ("broast", "Chicken broast / fried chicken", "Broast", ["fried chicken", "kfc chicken"], "piece", 130, 350, 25, 15, 21, "fastfood"),
    ("fried-fish", "Fried fish (masala)", "Fried machli", ["lahori fish"], "piece", 100, 250, 20, 10, 14, "bbq"),
    # daal and legumes
    ("daal-masoor", "Daal masoor", "Masoor ki daal", ["red lentils", "dal"], "bowl", 200, 230, 12, 30, 7, "daal"),
    ("daal-mash", "Daal mash", "Maash ki daal", ["urad dal", "daal maash"], "bowl", 200, 260, 14, 30, 9, "daal"),
    ("daal-chana", "Daal chana", "Chanay ki daal", ["chana dal"], "bowl", 200, 280, 14, 38, 8, "daal"),
    ("daal-moong", "Daal moong", "Moong ki daal", ["moong dal"], "bowl", 200, 220, 12, 30, 6, "daal"),
    ("daal-mix", "Mix daal", "Mix daal", [], "bowl", 200, 250, 13, 32, 8, "daal"),
    ("daal-makhani", "Daal makhani", "Daal makhani", [], "bowl", 200, 340, 13, 33, 17, "daal"),
    ("cholay", "Chana masala / cholay", "Cholay", ["chana masala", "chanay", "chickpea curry"], "bowl", 200, 300, 12, 40, 10, "daal"),
    ("lobia", "Lobia", "Lobia", ["black eyed beans"], "bowl", 200, 280, 13, 40, 8, "daal"),
    ("rajma", "Rajma", "Rajma", ["kidney beans"], "bowl", 200, 280, 13, 40, 8, "daal"),
    # sabzi
    ("aloo-sabzi", "Aloo sabzi / bhujia", "Aloo ki bhujia", ["aloo bhujia", "potato curry"], "bowl", 200, 260, 4, 34, 12, "sabzi"),
    ("bhindi", "Bhindi", "Bhindi", ["okra", "lady finger"], "bowl", 200, 200, 4, 14, 15, "sabzi"),
    ("aloo-gobhi", "Aloo gobhi", "Aloo gobhi", ["cauliflower potato"], "bowl", 200, 220, 5, 25, 12, "sabzi"),
    ("mix-sabzi", "Mix sabzi", "Mix sabzi", ["mixed vegetables"], "bowl", 200, 200, 5, 20, 11, "sabzi"),
    ("saag", "Sarson ka saag", "Saag", ["sarson saag"], "bowl", 200, 220, 6, 14, 16, "sabzi"),
    ("baingan-bharta", "Baingan bharta", "Baingan ka bharta", ["brinjal", "eggplant"], "bowl", 200, 200, 4, 15, 14, "sabzi"),
    ("karela", "Karela", "Karela", ["bitter gourd"], "bowl", 200, 180, 4, 14, 12, "sabzi"),
    ("aloo-matar", "Aloo matar", "Aloo matar", ["potato peas"], "bowl", 200, 240, 6, 30, 11, "sabzi"),
    ("tinda", "Tinda", "Tinday", ["tinday"], "bowl", 200, 150, 3, 12, 10, "sabzi"),
    ("lauki", "Lauki / ghiya", "Lauki", ["ghiya", "kaddu", "bottle gourd"], "bowl", 200, 140, 3, 12, 9, "sabzi"),
    ("tori", "Tori", "Tori", ["ridge gourd"], "bowl", 200, 150, 3, 12, 10, "sabzi"),
    ("palak-aloo", "Palak aloo", "Palak aloo", ["spinach potato"], "bowl", 200, 220, 5, 22, 13, "sabzi"),
    ("shimla-mirch-aloo", "Shimla mirch aloo", "Shimla mirch aloo", ["capsicum potato"], "bowl", 200, 220, 4, 24, 12, "sabzi"),
    ("salad", "Green salad (kheera, tamatar, piyaz)", "Salad", ["kachumber", "cucumber salad"], "plate", 150, 40, 2, 8, 0.3, "sabzi"),
    # eggs and breakfast
    ("egg-boiled", "Boiled egg", "Ubla anda", ["anda", "egg"], "piece", 50, 78, 6, 0.5, 5, "breakfast"),
    ("egg-fried", "Fried egg", "Fry anda", ["anda fry"], "piece", 55, 110, 6, 0.5, 9, "breakfast"),
    ("omelette", "Omelette (2 eggs, masala)", "Omelette", ["anda omelette"], "piece", 130, 220, 13, 3, 17, "breakfast"),
    ("egg-white", "Egg white", "Anday ki safedi", [], "piece", 33, 17, 3.6, 0.2, 0, "protein"),
    ("halwa-suji", "Suji halwa", "Halwa", ["halwa puri halwa", "sooji halwa"], "cup", 150, 450, 5, 55, 23, "sweet"),
    ("halwa-puri", "Halwa puri plate (2 puri, halwa, cholay)", "Halwa puri", ["nashta"], "plate", 450, 1000, 22, 115, 50, "breakfast"),
    ("french-toast", "French toast", "French toast", [], "slice", 65, 150, 6, 16, 7, "breakfast"),
    ("cornflakes-milk", "Cornflakes with milk", "Cornflakes", ["cereal"], "bowl", 280, 250, 9, 40, 6, "breakfast"),
    ("oats-milk", "Oats porridge with milk", "Daliya", ["oatmeal", "dalia"], "bowl", 250, 280, 11, 42, 7, "breakfast"),
    ("nihari-naan", "Nihari with naan", "Nihari naan", [], "plate", 350, 770, 39, 60, 41, "curry"),
    # dairy
    ("milk-full", "Milk (full cream)", "Doodh", ["milk", "doodh"], "glass", 250, 160, 8, 12, 9, "dairy"),
    ("milk-low", "Milk (low fat)", "Low fat doodh", ["skimmed milk"], "glass", 250, 110, 8, 12, 3, "dairy"),
    ("dahi", "Yogurt (dahi)", "Dahi", ["yogurt", "curd"], "cup", 200, 122, 7, 9, 6.5, "dairy"),
    ("raita", "Raita", "Raita", [], "bowl", 150, 100, 4, 8, 5, "dairy"),
    ("lassi-sweet", "Lassi (sweet)", "Meethi lassi", ["lassi"], "glass", 300, 250, 8, 40, 7, "drink"),
    ("lassi-salty", "Lassi (salty)", "Namkeen lassi", ["chaach"], "glass", 300, 150, 8, 10, 8, "drink"),
    ("paneer", "Paneer", "Paneer", ["cottage cheese"], "100 g", 100, 265, 18, 3, 20, "dairy"),
    ("cheese-slice", "Cheese slice", "Cheese", [], "slice", 20, 70, 4, 1, 5, "dairy"),
    ("butter", "Butter", "Makhan", ["makhan"], "tbsp", 14, 100, 0, 0, 11, "fat"),
    ("ghee", "Desi ghee", "Ghee", ["desi ghee"], "tbsp", 14, 120, 0, 0, 14, "fat"),
    ("oil", "Cooking oil", "Tail", ["oil", "banaspati"], "tbsp", 14, 120, 0, 0, 14, "fat"),
    ("cream", "Cream", "Malai", ["malai"], "tbsp", 15, 50, 0.3, 0.5, 5, "fat"),
    # drinks
    ("chai-doodh-patti", "Doodh patti chai (with sugar)", "Doodh patti", ["doodh pati", "milk tea"], "cup", 200, 120, 4, 14, 5, "drink"),
    ("chai", "Chai (1 tsp sugar)", "Chai", ["tea", "chaye"], "cup", 150, 80, 2.5, 9, 3.5, "drink"),
    ("chai-no-sugar", "Chai (no sugar)", "Bina cheeni chai", ["tea no sugar"], "cup", 150, 50, 2.5, 4, 3, "drink"),
    ("green-tea", "Green tea / qehwa", "Qehwa", ["kahwa", "green tea"], "cup", 200, 2, 0, 0.5, 0, "drink"),
    ("coffee-black", "Black coffee", "Black coffee", [], "cup", 200, 5, 0.3, 0, 0, "drink"),
    ("cappuccino", "Cappuccino / latte", "Coffee", ["latte"], "cup", 250, 120, 6, 10, 6, "drink"),
    ("soft-drink", "Soft drink (can)", "Cold drink", ["coke", "pepsi", "sprite", "7up"], "can", 330, 140, 0, 35, 0, "drink"),
    ("soft-drink-diet", "Diet soft drink (can)", "Diet cold drink", ["diet coke", "pepsi zero"], "can", 330, 1, 0, 0, 0, "drink"),
    ("juice-pack", "Juice (packet)", "Juice", ["nestle juice", "frooto"], "pack", 200, 100, 0, 25, 0, "drink"),
    ("orange-juice", "Fresh orange juice", "Malta juice", ["mosambi juice"], "glass", 250, 110, 2, 26, 0.5, "drink"),
    ("ganne-ka-ras", "Sugarcane juice", "Gannay ka ras", ["sugarcane"], "glass", 250, 180, 0, 45, 0, "drink"),
    ("rooh-afza-milk", "Rooh afza with milk", "Doodh soda / Rooh afza", ["sharbat"], "glass", 250, 220, 8, 32, 7, "drink"),
    ("mango-shake", "Mango shake", "Aam ka shake", [], "glass", 300, 300, 8, 50, 8, "drink"),
    ("banana-shake", "Banana shake", "Kaylay ka shake", [], "glass", 300, 280, 9, 45, 7, "drink"),
    ("sugar", "Sugar", "Cheeni", ["cheeni", "shakkar"], "tsp", 4, 16, 0, 4, 0, "sweet"),
    # snacks and street food
    ("samosa", "Samosa (aloo)", "Samosa", ["samosay"], "piece", 90, 260, 4, 30, 14, "snack"),
    ("samosa-qeema", "Samosa (qeema)", "Qeema samosa", [], "piece", 80, 280, 9, 22, 17, "snack"),
    ("pakora", "Pakoray (5 pieces)", "Pakoray", ["pakora", "bhajia"], "serving", 100, 300, 8, 25, 18, "snack"),
    ("bread-pakora", "Bread pakora", "Bread pakora", [], "piece", 90, 250, 6, 25, 14, "snack"),
    ("dahi-bhalla", "Dahi bhallay", "Dahi bhallay", ["dahi baray"], "plate", 200, 280, 9, 38, 10, "snack"),
    ("chana-chaat", "Chana chaat", "Chana chaat", ["chaat"], "plate", 200, 250, 10, 40, 6, "snack"),
    ("fruit-chaat", "Fruit chaat", "Fruit chaat", [], "bowl", 200, 150, 2, 36, 0.5, "fruit"),
    ("gol-gappay", "Gol gappay (6)", "Gol gappay", ["pani puri"], "serving", 120, 200, 4, 32, 7, "snack"),
    ("spring-roll", "Spring roll", "Roll", [], "piece", 60, 150, 4, 16, 8, "snack"),
    ("bun-kebab", "Bun kebab", "Bun kabab", ["anda shami burger"], "piece", 180, 400, 15, 45, 17, "fastfood"),
    ("shawarma", "Chicken shawarma", "Shawarma", [], "piece", 250, 450, 25, 45, 18, "fastfood"),
    ("zinger", "Zinger burger", "Zinger", ["zinger burger"], "piece", 220, 550, 25, 50, 27, "fastfood"),
    ("chicken-burger", "Chicken burger", "Chicken burger", [], "piece", 180, 450, 22, 45, 20, "fastfood"),
    ("beef-burger", "Beef burger", "Beef burger", [], "piece", 200, 500, 25, 42, 25, "fastfood"),
    ("pizza-slice", "Pizza slice (medium)", "Pizza", [], "slice", 110, 280, 12, 32, 11, "fastfood"),
    ("fries", "French fries (regular)", "Fries", ["chips"], "serving", 115, 320, 4, 42, 15, "fastfood"),
    ("paratha-roll", "Chicken paratha roll", "Paratha roll", ["roll paratha"], "piece", 220, 550, 22, 50, 29, "fastfood"),
    ("nimko", "Nimko / mixture", "Nimko", ["namkeen", "mixture"], "handful", 30, 160, 4, 15, 10, "snack"),
    ("chips-packet", "Chips (small packet)", "Chips", ["lays", "slanty"], "pack", 30, 160, 2, 16, 10, "snack"),
    ("biscuit", "Biscuits (2 tea biscuits)", "Biscuit", ["cookies", "tuc", "marie"], "serving", 16, 75, 1, 11, 3, "snack"),
    ("bhunay-chanay", "Roasted chickpeas", "Bhunay chanay", ["chanay"], "handful", 30, 110, 6, 18, 2, "snack"),
    ("peanuts", "Peanuts", "Moong phali", ["mungphali"], "handful", 30, 170, 7, 5, 14, "snack"),
    ("almonds", "Almonds (10)", "Badaam", ["badam"], "serving", 12, 70, 2.5, 2.5, 6, "snack"),
    ("dates", "Dates", "Khajoor", ["khajoor", "khajur"], "piece", 8, 23, 0.2, 6, 0, "fruit"),
    # sweets
    ("gulab-jamun", "Gulab jamun", "Gulab jamun", [], "piece", 50, 150, 2, 25, 5, "sweet"),
    ("jalebi", "Jalebi", "Jalebi", [], "100 g", 100, 400, 3, 60, 17, "sweet"),
    ("kheer", "Kheer", "Kheer", ["rice pudding"], "bowl", 150, 250, 7, 38, 8, "sweet"),
    ("gajar-halwa", "Gajar halwa", "Gajar ka halwa", ["carrot halwa"], "cup", 150, 350, 6, 45, 17, "sweet"),
    ("barfi", "Barfi", "Barfi", ["mithai"], "piece", 30, 130, 3, 17, 6, "sweet"),
    ("laddu", "Laddu", "Laddu", ["ladoo"], "piece", 40, 180, 3, 22, 9, "sweet"),
    ("rasmalai", "Rasmalai", "Rasmalai", [], "piece", 80, 200, 6, 22, 10, "sweet"),
    ("firni", "Firni", "Firni", ["phirni"], "bowl", 150, 220, 6, 34, 7, "sweet"),
    ("sheer-khurma", "Sheer khurma", "Sheer khurma", [], "bowl", 200, 400, 10, 50, 18, "sweet"),
    ("custard", "Custard", "Custard", [], "bowl", 150, 200, 5, 30, 6, "sweet"),
    ("ice-cream", "Ice cream (1 scoop)", "Ice cream", ["kulfi"], "scoop", 70, 140, 2.5, 16, 7, "sweet"),
    ("cake-slice", "Cake slice", "Cake", [], "slice", 80, 300, 4, 40, 14, "sweet"),
    # fruit
    ("banana", "Banana", "Kayla", ["kela"], "piece", 118, 105, 1.3, 27, 0.4, "fruit"),
    ("apple", "Apple", "Saib", ["seb"], "piece", 180, 95, 0.5, 25, 0.3, "fruit"),
    ("orange", "Orange / kinnow", "Kinnow", ["malta", "santra"], "piece", 130, 62, 1.2, 15, 0.2, "fruit"),
    ("mango", "Mango", "Aam", ["aam"], "piece", 200, 120, 1.6, 30, 0.8, "fruit"),
    ("guava", "Guava", "Amrood", ["amrud"], "piece", 100, 68, 2.6, 14, 1, "fruit"),
    ("grapes", "Grapes", "Angoor", [], "cup", 100, 69, 0.7, 18, 0.2, "fruit"),
    ("watermelon", "Watermelon", "Tarbooz", ["tarbuz"], "slice", 300, 90, 1.8, 23, 0.5, "fruit"),
    ("melon", "Melon", "Kharbooza", ["garma"], "slice", 200, 70, 1.7, 16, 0.4, "fruit"),
    ("pomegranate", "Pomegranate", "Anaar", ["anar"], "piece", 175, 145, 3, 33, 2, "fruit"),
    ("papaya", "Papaya", "Papita", [], "cup", 140, 60, 0.7, 15, 0.4, "fruit"),
    # plain protein
    ("chicken-breast", "Chicken breast (grilled)", "Chicken breast", ["grilled chicken"], "100 g", 100, 165, 31, 0, 3.6, "protein"),
    ("chicken-thigh", "Chicken thigh (cooked)", "Chicken thigh", [], "100 g", 100, 210, 26, 0, 11, "protein"),
    ("beef-cooked", "Beef (cooked, lean)", "Gosht", ["bari ka gosht"], "100 g", 100, 220, 27, 0, 12, "protein"),
    ("mutton-cooked", "Mutton (cooked)", "Mutton", ["bakra gosht"], "100 g", 100, 280, 25, 0, 20, "protein"),
    ("fish-grilled", "Fish (grilled)", "Machli", ["fish"], "100 g", 100, 150, 25, 0, 5, "protein"),
    ("tuna-can", "Tuna (canned in water)", "Tuna", [], "100 g", 100, 120, 26, 0, 1, "protein"),
    ("whey", "Whey protein (1 scoop)", "Protein shake", ["protein powder"], "scoop", 30, 120, 24, 3, 1.5, "protein"),
    # condiments
    ("ketchup", "Ketchup", "Ketchup", ["sauce"], "tbsp", 17, 20, 0.2, 5, 0, "condiment"),
    ("mayo", "Mayonnaise", "Mayo", [], "tbsp", 14, 95, 0.1, 0.1, 10, "condiment"),
    ("imli-chutney", "Imli chutney", "Imli ki chutney", ["tamarind chutney"], "tbsp", 20, 30, 0, 8, 0, "condiment"),
    ("achar", "Achar (pickle)", "Achar", ["pickle"], "tbsp", 15, 45, 0.2, 1, 4.5, "condiment"),
]

FEDB_LICENSE = "Unlicense (public domain), github.com/yuhonas/free-exercise-db"

# Ladders, easiest first. type: weight_reps | reps | time.  equipment: dumbbell | bar | none.
# per_side: reps/time counted per side. img: free-exercise-db id (closest match where an exact one is missing).
LADDERS = {
    "push": [
        ("wall-pushup", "Wall push-up", "reps", "none", False, "Incline_Push-Up",
         ["Hands on a wall at shoulder height, a bit wider than shoulders (the demo uses a box; a wall is easier)", "Body in one straight line from head to heels",
          "Lower your chest to the wall, elbows at about 45°", "Push back until arms are straight"],
         ["Sagging hips or sticking your bum out", "Flaring elbows straight out to the sides"], ["chest", "triceps", "shoulders"]),
        ("incline-pushup", "Incline push-up (hands on table)", "reps", "none", False, "Incline_Push-Up",
         ["Hands on a sturdy table or bed edge", "Brace your stomach and squeeze your glutes", "Lower until your chest nearly touches the edge", "Press up to straight arms"],
         ["Letting the hips drop", "Only doing half the range"], ["chest", "triceps", "shoulders"]),
        ("knee-pushup", "Knee push-up", "reps", "none", False, "Pushups",
         ["Knees on the floor, hands under shoulders (the demo shows the full version)", "Straight line from head to knees", "Lower your chest to a fist's height from the floor", "Push the floor away"],
         ["Hips bent like a hinge", "Head dropping forward"], ["chest", "triceps", "shoulders"]),
        ("pushup", "Push-up", "reps", "none", False, "Pushups",
         ["Hands under shoulders, feet together", "Squeeze glutes and brace like a plank", "Lower your chest to just above the floor", "Push up while keeping the body rigid"],
         ["Hips sagging", "Elbows flared at 90°"], ["chest", "triceps", "shoulders", "core"]),
        ("decline-pushup", "Feet-elevated push-up", "reps", "none", False, "Decline_Push-Up",
         ["Feet on a step or low chair, hands on the floor", "Body in a straight line", "Lower slowly, chest towards the floor", "Push up fully"],
         ["Piking the hips up", "Rushing the lowering"], ["upper chest", "shoulders", "triceps"]),
    ],
    "squat": [
        ("chair-squat", "Chair squat", "reps", "none", False, "Bodyweight_Squat",
         ["Stand in front of a chair, feet shoulder-width (the demo shows the same squat without the chair)", "Sit back and down until you touch the chair", "Keep your chest up and knees over toes", "Stand up by pushing through your heels"],
         ["Dropping onto the chair", "Knees caving inwards"], ["quads", "glutes"]),
        ("bodyweight-squat", "Bodyweight squat", "reps", "none", False, "Bodyweight_Squat",
         ["Feet shoulder-width, toes slightly out", "Arms forward for balance", "Squat as deep as is comfortable with a flat back", "Drive up through the whole foot"],
         ["Heels lifting", "Rounding the lower back at the bottom"], ["quads", "glutes", "hamstrings"]),
        ("goblet-squat", "Goblet squat (dumbbell)", "weight_reps", "dumbbell", False, "Goblet_Squat",
         ["Hold one dumbbell vertically at your chest", "Elbows point down, chest stays tall", "Squat between your knees", "Stand up tall and squeeze your glutes"],
         ["Letting the weight pull you forward", "Knees caving in"], ["quads", "glutes", "core"]),
        ("split-squat", "Split squat (dumbbells)", "weight_reps", "dumbbell", True, "Split_Squat_with_Dumbbells",
         ["Long stride stance, dumbbells at your sides", "Lower the back knee straight down towards the floor", "Front knee stays over the foot", "Push through the front heel to rise"],
         ["Stance too short", "Leaning far forward"], ["quads", "glutes"]),
        ("step-up", "Dumbbell step-up", "weight_reps", "dumbbell", True, "Dumbbell_Step_Ups",
         ["Use a sturdy step or low bench", "Place the whole foot on the step", "Drive up through that heel, don't push off the back foot", "Step down slowly"],
         ["Pushing off the back leg", "Wobbly knee"], ["quads", "glutes"]),
    ],
    "lunge": [
        ("reverse-lunge", "Reverse lunge", "weight_reps", "dumbbell", True, "Dumbbell_Rear_Lunge",
         ["Stand tall (dumbbells at sides once bodyweight is easy)", "Step one foot back and lower both knees", "Front shin stays roughly vertical", "Push through the front foot to return"],
         ["Front knee shooting past the toes", "Short wobbly steps"], ["quads", "glutes", "hamstrings"]),
        ("forward-lunge", "Forward lunge (dumbbells)", "weight_reps", "dumbbell", True, "Dumbbell_Lunges",
         ["Dumbbells at your sides", "Step forward and lower the back knee", "Keep the torso upright", "Push back to standing"],
         ["Back knee slamming the floor", "Leaning the torso forward"], ["quads", "glutes"]),
        ("walking-lunge", "Walking lunge", "weight_reps", "dumbbell", True, "Bodyweight_Walking_Lunge",
         ["Lunge forward, then bring the back foot through into the next lunge", "Keep steps long and controlled", "Torso upright", "Count reps per leg"],
         ["Rushing", "Narrow tightrope stance"], ["quads", "glutes", "hamstrings"]),
    ],
    "hinge": [
        ("glute-bridge", "Glute bridge", "reps", "none", False, "Butt_Lift_Bridge",
         ["Lie on your back, knees bent, feet flat", "Push through your heels and lift the hips", "Squeeze glutes hard at the top for 1 second", "Lower slowly"],
         ["Arching the lower back instead of using glutes", "Feet too far from the hips"], ["glutes", "hamstrings"]),
        ("bar-rdl", "Romanian deadlift (bar)", "weight_reps", "bar", False, "Romanian_Deadlift",
         ["Hold the bar at your thighs, soft knees", "Push your hips back, bar slides down your legs", "Back stays flat; stop when you feel the hamstrings stretch", "Drive hips forward to stand"],
         ["Rounding the back", "Squatting the weight down instead of hinging"], ["hamstrings", "glutes", "lower back"]),
        ("single-leg-bridge", "Single-leg glute bridge", "reps", "none", True, "Single_Leg_Glute_Bridge",
         ["One foot on the floor, the other leg straight up", "Lift the hips by pushing through the planted heel", "Keep the hips level", "Lower slowly"],
         ["Hips tilting to one side", "Pushing through the toes"], ["glutes", "hamstrings"]),
    ],
    "row": [
        ("db-row", "Bent-over dumbbell row", "weight_reps", "dumbbell", False, "Bent_Over_Two-Dumbbell_Row",
         ["Hinge forward with a flat back, dumbbells hanging", "Pull the dumbbells to your lower ribs", "Squeeze your shoulder blades together", "Lower under control"],
         ["Rounding the back", "Jerking the weight with the body"], ["upper back", "lats", "biceps"]),
        ("one-arm-row", "One-arm dumbbell row", "weight_reps", "dumbbell", True, "One-Arm_Dumbbell_Row",
         ["Support one hand and knee on a bed or chair", "Pull the dumbbell towards your hip", "Pause at the top, elbow close to the body", "Lower slowly to a full stretch"],
         ["Twisting the torso to lift", "Shrugging the shoulder"], ["lats", "upper back", "biceps"]),
        ("bar-row", "Bent-over bar row", "weight_reps", "bar", False, "Bent_Over_Barbell_Row",
         ["Hinge to about 45°, flat back", "Pull the bar to your belly button", "Elbows go back, not out", "Lower with control"],
         ["Standing up as you pull", "Rounding the back"], ["upper back", "lats", "biceps"]),
    ],
    "press": [
        ("db-floor-press", "Dumbbell floor press", "weight_reps", "dumbbell", False, "Dumbbell_Floor_Press",
         ["Lie on the floor, knees bent, dumbbells over the chest", "Lower until the upper arms touch the floor", "Pause briefly", "Press up until arms are straight"],
         ["Bouncing elbows off the floor", "Wrists bent back"], ["chest", "triceps"]),
        ("db-shoulder-press", "Dumbbell shoulder press", "weight_reps", "dumbbell", False, "Dumbbell_Shoulder_Press",
         ["Sit or stand tall, dumbbells at shoulder height", "Brace your stomach", "Press overhead until arms are straight", "Lower to the shoulders slowly"],
         ["Arching the lower back", "Pressing forward instead of up"], ["shoulders", "triceps"]),
        ("one-arm-press", "One-arm dumbbell press", "weight_reps", "dumbbell", True, "Dumbbell_One-Arm_Shoulder_Press",
         ["Stand tall, one dumbbell at the shoulder", "Brace hard so you don't lean", "Press straight up", "Lower slowly"],
         ["Leaning to the side", "Shrugging"], ["shoulders", "triceps", "core"]),
    ],
    "core-a": [
        ("plank", "Plank", "time", "none", False, "Plank",
         ["Forearms on the floor, elbows under shoulders", "Straight line from head to heels (knees down to start if needed)", "Squeeze glutes and brace the stomach", "Breathe slowly"],
         ["Hips too high or sagging", "Holding your breath"], ["core", "shoulders"]),
        ("side-plank", "Side plank", "time", "none", True, "Side_Bridge",
         ["Lie on your side, elbow under the shoulder", "Lift the hips into a straight line (bottom knee down to start)", "Don't let the hips sink", "Switch sides"],
         ["Hips sagging", "Rolling forward"], ["obliques", "core"]),
        ("pushup-side-plank", "Push-up to side plank", "reps", "none", False, "Push_Up_to_Side_Plank",
         ["Do a push-up", "At the top rotate into a side plank, arm up", "Return and repeat to the other side", "Move slowly and controlled"],
         ["Rushing the rotation", "Hips sagging"], ["core", "chest", "shoulders"]),
    ],
    "core-b": [
        ("dead-bug", "Dead bug", "reps", "none", True, "Dead_Bug",
         ["Lie on your back, arms up, knees bent at 90°", "Press your lower back into the floor", "Slowly extend opposite arm and leg", "Return and switch sides"],
         ["Lower back lifting off the floor", "Moving fast"], ["deep core"]),
        ("superman", "Superman", "reps", "none", False, "Superman",
         ["Lie face down, arms forward", "Lift arms, chest and legs a little off the floor", "Hold 2 seconds", "Lower slowly"],
         ["Cranking the neck up", "Jerking up"], ["lower back", "glutes"]),
        ("mountain-climber", "Mountain climbers (slow)", "time", "none", False, "Mountain_Climbers",
         ["Start in a high plank", "Drive one knee towards the chest, then switch", "Keep the hips level", "Start slow; speed comes later"],
         ["Hips bouncing up", "Hands drifting forward"], ["core", "shoulders", "cardio"]),
    ],
}

# Dumbbell exercises that start with bodyweight only (load 0) before adding dumbbells.
BODYWEIGHT_START = {"split-squat", "step-up", "reverse-lunge", "forward-lunge", "walking-lunge"}

# Which ladders each strength session uses.
SESSIONS = {
    "strength-a": ["squat", "push", "row", "hinge", "core-a"],
    "strength-b": ["lunge", "push", "press", "row", "core-b"],
}


def build_exercises() -> dict:
    exercises = []
    for ladder, steps in LADDERS.items():
        for step, (eid, name, typ, equip, per_side, img, cues, mistakes, muscles) in enumerate(steps):
            exercises.append({
                "id": eid, "name": name, "ladder": ladder, "step": step, "type": typ,
                "equipment": equip, "perSide": per_side, "cues": cues, "mistakes": mistakes,
                "muscles": muscles, "fedbId": img, "bodyweightStart": eid in BODYWEIGHT_START,
                "images": [f"exercises/{eid}/0.jpg", f"exercises/{eid}/1.jpg"],
                "video": "https://www.youtube.com/results?search_query="
                         + urllib.request.quote(f"{name} proper form beginner"),
            })
    return {"version": 1, "mediaLicense": FEDB_LICENSE, "sessions": SESSIONS, "exercises": exercises}


def build_foods() -> dict:
    ids = set()
    foods = []
    for fid, en, ur, aliases, unit, grams, kcal, p, c, f, cat in FOODS:
        assert fid not in ids, f"duplicate food id {fid}"
        ids.add(fid)
        # sanity: energy from macros within 25% (or 40 kcal) of the stated kcal
        macro_kcal = 4 * p + 4 * c + 9 * f
        assert abs(macro_kcal - kcal) <= max(40, 0.25 * kcal), f"{fid}: macros {macro_kcal:.0f} vs {kcal}"
        foods.append({"id": fid, "nameEn": en, "nameUrdu": ur, "aliases": aliases, "unit": unit,
                      "grams": grams, "kcal": kcal, "protein": p, "carbs": c, "fat": f, "category": cat})
    return {"version": 1, "foods": foods}


def fetch_media(exercises: list[dict]) -> None:
    for ex in exercises:
        out = DATA / "exercises" / ex["id"]
        out.mkdir(parents=True, exist_ok=True)
        for i in (0, 1):
            dest = out / f"{i}.jpg"
            if dest.exists():
                continue
            url = f"{FEDB_RAW}/{ex['fedbId']}/{i}.jpg"
            with urllib.request.urlopen(url, timeout=30) as r:
                body = r.read()
            if not body.startswith(b"\xff\xd8"):
                raise SystemExit(f"{url} is not a JPEG")
            dest.write_bytes(body)
    (DATA / "exercises" / "LICENSE.txt").write_text(
        f"Exercise demo images: {FEDB_LICENSE}\n")


def main() -> None:
    DATA.mkdir(exist_ok=True)
    foods, exercises = build_foods(), build_exercises()
    (DATA / "foods_pk.json").write_text(json.dumps(foods, ensure_ascii=False, indent=1) + "\n")
    (DATA / "exercises.json").write_text(json.dumps(exercises, ensure_ascii=False, indent=1) + "\n")
    print(f"{len(foods['foods'])} foods, {len(exercises['exercises'])} exercises")
    if "--media" in sys.argv:
        fetch_media(exercises["exercises"])
        print("media fetched")


if __name__ == "__main__":
    main()
